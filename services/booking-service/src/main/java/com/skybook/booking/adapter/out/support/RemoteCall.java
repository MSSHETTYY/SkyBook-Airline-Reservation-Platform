package com.skybook.booking.adapter.out.support;

import java.io.IOException;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;

import com.skybook.booking.domain.model.BookingErrorCode;
import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.error.ErrorCode;
import com.skybook.common.error.SkyBookException;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared resilience and error translation for calls to other services and partners.
 *
 * <p>Order of decorators: retry( circuit breaker( bulkhead( call ) ) ). The bulkhead caps concurrent
 * calls so a slow partner cannot exhaust request threads; the circuit breaker fails fast while the
 * partner is down; retries with backoff absorb short blips. Transient failures (I/O, timeouts, 5xx, 429)
 * are retried and counted; business rejections (4xx) are neither. Callers only see
 * {@link SkyBookException}s: the partner's own problem code, or {@code SERVICE_UNAVAILABLE}.
 */
public final class RemoteCall {

    private final String partner;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Bulkhead bulkhead;
    private final ObjectMapper objectMapper;

    public RemoteCall(String partner, CircuitBreaker circuitBreaker, Retry retry, Bulkhead bulkhead,
                      ObjectMapper objectMapper) {
        this.partner = partner;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.bulkhead = bulkhead;
        this.objectMapper = objectMapper;
    }

    /** A transient failure: retried, and recorded by the circuit breaker. */
    public static final class Unavailable extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A business rejection: never retried, ignored by the circuit breaker. */
    public static final class Rejected extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final transient SkyBookException problem;

        public Rejected(SkyBookException problem) {
            super(problem.getMessage(), problem);
            this.problem = problem;
        }

        public SkyBookException problem() {
            return problem;
        }
    }

    public <T> T execute(String operation, Supplier<T> call) {
        Supplier<T> guarded = () -> {
            try {
                return call.get();
            } catch (ResourceAccessException ex) {
                throw new Unavailable(partner + " " + operation + ": " + ex.getMessage(), ex);
            }
        };
        Supplier<T> decorated = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, Bulkhead.decorateSupplier(bulkhead, guarded)));
        try {
            return decorated.get();
        } catch (Rejected ex) {
            throw ex.problem();
        } catch (Unavailable | CallNotPermittedException | BulkheadFullException ex) {
            throw new SkyBookException(CommonErrorCode.SERVICE_UNAVAILABLE,
                    "%s is temporarily unavailable. Please try again shortly.".formatted(partner), Map.of(), ex);
        }
    }

    public void execute(String operation, Runnable call) {
        execute(operation, () -> {
            call.run();
            return null;
        });
    }

    /** RestClient status handler: classifies an error response as transient or as a business rejection. */
    public void raise(HttpRequest request, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status >= 500 || status == 429) {
            throw new Unavailable("HTTP " + status + " from " + request.getURI(), null);
        }
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        String code = null;
        String detail = "%s rejected the request (HTTP %d)".formatted(partner, status);
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
        } catch (RuntimeException notJson) {
            // Not a problem document; keep the generic detail.
        }
        throw new Rejected(new SkyBookException(toErrorCode(code, status), detail, properties, null));
    }

    /** Keeps known SkyBook codes from the remote problem; otherwise derives one from the status. */
    public static ErrorCode toErrorCode(String remoteCode, int status) {
        if (remoteCode != null) {
            for (BookingErrorCode candidate : BookingErrorCode.values()) {
                if (candidate.code().equals(remoteCode)) {
                    return candidate;
                }
            }
            for (CommonErrorCode candidate : CommonErrorCode.values()) {
                if (candidate.code().equals(remoteCode)) {
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
}
