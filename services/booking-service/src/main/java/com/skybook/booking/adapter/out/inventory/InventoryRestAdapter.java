package com.skybook.booking.adapter.out.inventory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.skybook.booking.adapter.out.inventory.model.ConfirmHoldRequest;
import com.skybook.booking.adapter.out.inventory.model.HoldSeatsRequest;
import com.skybook.booking.adapter.out.inventory.model.SeatReleaseRequest;
import com.skybook.booking.application.port.out.InventoryPort;
import com.skybook.booking.domain.model.BookingErrorCode;
import com.skybook.booking.domain.model.FlightSnapshot;
import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.error.ErrorCode;
import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;
import com.skybook.common.web.CorrelationId;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link InventoryPort} over HTTP, using the DTOs generated from inventory's OpenAPI spec.
 *
 * <p>Resilience: every call has connect/read timeouts (set on the RestClient), is retried with
 * exponential backoff on transient failures (I/O errors, 5xx, 429), and goes through a circuit breaker
 * so a struggling inventory service is not hammered. Retrying is safe because every inventory
 * operation is idempotent (holds carry an Idempotency-Key; confirm and release are idempotent by design).
 * Business rejections (4xx) are never retried and do not count as circuit-breaker failures.
 */
public class InventoryRestAdapter implements InventoryPort {

    private static final Logger LOG = LoggerFactory.getLogger(InventoryRestAdapter.class);

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final ObjectMapper objectMapper;

    public InventoryRestAdapter(RestClient restClient, CircuitBreaker circuitBreaker, Retry retry,
                                ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.objectMapper = objectMapper;
    }

    @Override
    public SeatHold holdSeats(Long flightId, Collection<String> seatNumbers, String idempotencyKey) {
        var response = call("holdSeats", () -> restClient.post()
                .uri("/api/v1/flights/{flightId}/holds", flightId)
                .header("Idempotency-Key", idempotencyKey)
                .header(CorrelationId.HEADER, CorrelationId.current().orElse(null))
                .body(new HoldSeatsRequest().seatNumbers(new LinkedHashSet<>(seatNumbers)))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raise)
                .body(com.skybook.booking.adapter.out.inventory.model.SeatHold.class));
        String currency = response.getTotalPrice().getCurrency();
        List<HeldSeat> seats = new ArrayList<>();
        response.getSeats().forEach(seat ->
                seats.add(new HeldSeat(seat.getSeatNumber(), Money.of(seat.getPrice().getAmount(), currency))));
        return new SeatHold(response.getHoldId().toString(),
                new FlightSnapshot(response.getFlightId(), response.getFlightNumber(), response.getOrigin(),
                        response.getDestination(), response.getDepartureTime().toInstant()),
                seats, Money.of(response.getTotalPrice().getAmount(), currency), response.getExpiresAt().toInstant());
    }

    @Override
    public void confirmHold(String holdId, String bookingReference) {
        call("confirmHold", () -> restClient.post()
                .uri("/api/v1/holds/{holdId}/confirmation", holdId)
                .header(CorrelationId.HEADER, CorrelationId.current().orElse(null))
                .body(new ConfirmHoldRequest().bookingReference(bookingReference))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raise)
                .toBodilessEntity());
    }

    @Override
    public void releaseHold(String holdId) {
        call("releaseHold", () -> restClient.delete()
                .uri("/api/v1/holds/{holdId}", holdId)
                .header(CorrelationId.HEADER, CorrelationId.current().orElse(null))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raise)
                .toBodilessEntity());
    }

    @Override
    public void releaseBookedSeats(Long flightId, String bookingReference) {
        call("releaseBookedSeats", () -> restClient.post()
                .uri("/api/v1/flights/{flightId}/seat-releases", flightId)
                .header(CorrelationId.HEADER, CorrelationId.current().orElse(null))
                .body(new SeatReleaseRequest().bookingReference(bookingReference))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raise)
                .toBodilessEntity());
    }

    private <T> T call(String operation, Supplier<T> request) {
        Supplier<T> guarded = () -> {
            try {
                return request.get();
            } catch (ResourceAccessException ex) {
                throw new InventoryUnavailableException(operation + ": " + ex.getMessage(), ex);
            }
        };
        try {
            // Retry wraps the circuit breaker: every attempt is recorded, and an open circuit stops retries.
            return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, guarded)).get();
        } catch (InventoryRejectedException ex) {
            throw ex.getProblem();
        } catch (CallNotPermittedException ex) {
            LOG.warn("Inventory circuit breaker is open; failing fast on {}", operation);
            throw unavailable();
        } catch (InventoryUnavailableException ex) {
            LOG.warn("Inventory {} failed after retries: {}", operation, ex.getMessage());
            throw unavailable();
        }
    }

    /** Translates an error response into a retryable outage or a non-retryable business rejection. */
    private void raise(HttpRequest request, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status >= 500 || status == 429) {
            throw new InventoryUnavailableException("HTTP " + status + " from " + request.getURI(), null);
        }
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        String code = null;
        String detail = "Inventory rejected the request (HTTP " + status + ")";
        Map<String, Object> properties = new LinkedHashMap<>();
        try {
            JsonNode problem = objectMapper.readTree(body);
            code = problem.path("code").isString() ? problem.path("code").asString() : null;
            if (problem.path("detail").isString()) {
                detail = problem.path("detail").asString();
            }
            if (problem.path("seatNumbers").isArray()) {
                List<String> seats = new ArrayList<>();
                problem.path("seatNumbers").forEach(node -> seats.add(node.asString()));
                properties.put("seatNumbers", seats);
            }
        } catch (RuntimeException ignored) {
            // Not a problem document; keep the generic detail.
        }
        throw new InventoryRejectedException(new SkyBookException(toBookingCode(code, status), detail, properties,
                null));
    }

    static ErrorCode toBookingCode(String inventoryCode, int status) {
        if (inventoryCode != null) {
            for (BookingErrorCode candidate : BookingErrorCode.values()) {
                if (candidate.code().equals(inventoryCode)) {
                    return candidate;
                }
            }
            for (CommonErrorCode candidate : CommonErrorCode.values()) {
                if (candidate.code().equals(inventoryCode)) {
                    return candidate;
                }
            }
        }
        return switch (status) {
            case 404 -> CommonErrorCode.RESOURCE_NOT_FOUND;
            case 409 -> CommonErrorCode.CONFLICT;
            case 400 -> CommonErrorCode.VALIDATION_FAILED;
            default -> CommonErrorCode.BUSINESS_RULE_VIOLATION;
        };
    }

    private static SkyBookException unavailable() {
        return new SkyBookException(CommonErrorCode.SERVICE_UNAVAILABLE,
                "Seat inventory is temporarily unavailable. Please try again shortly.");
    }
}
