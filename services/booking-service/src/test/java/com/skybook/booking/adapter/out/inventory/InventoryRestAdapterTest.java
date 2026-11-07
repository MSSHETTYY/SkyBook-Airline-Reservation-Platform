package com.skybook.booking.adapter.out.inventory;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.skybook.booking.application.port.out.InventoryPort;
import com.skybook.booking.domain.model.BookingErrorCode;
import com.skybook.booking.support.InventoryStubs;
import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import tools.jackson.databind.json.JsonMapper;

class InventoryRestAdapterTest {

    private static final Instant DEPARTURE = Instant.parse("2026-12-11T08:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-12-01T08:15:00Z");

    private static WireMockServer server;

    private CircuitBreaker circuitBreaker;
    private InventoryRestAdapter adapter;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        server.resetAll();
        circuitBreaker = CircuitBreaker.of("inventory", CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .recordExceptions(InventoryUnavailableException.class)
                .build());
        Retry retry = Retry.of("inventory", RetryConfig.custom()
                .maxAttempts(3).waitDuration(Duration.ofMillis(10))
                .retryExceptions(InventoryUnavailableException.class)
                .build());
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
        requestFactory.setReadTimeout(Duration.ofMillis(500));
        RestClient restClient = RestClient.builder().baseUrl(server.baseUrl()).requestFactory(requestFactory).build();
        adapter = new InventoryRestAdapter(restClient, circuitBreaker, retry, JsonMapper.builder().build());
    }

    @Test
    void mapsASuccessfulHold() {
        String holdId = InventoryStubs.stubHold(server, 7L, DEPARTURE, EXPIRES, List.of("12A", "12B"), "110.00");

        InventoryPort.SeatHold hold = adapter.holdSeats(7L, List.of("12A", "12B"), "bk-key");

        assertThat(hold.holdId()).isEqualTo(holdId);
        assertThat(hold.flight().flightNumber()).isEqualTo("SB101");
        assertThat(hold.flight().departureTime()).isEqualTo(DEPARTURE);
        assertThat(hold.total()).isEqualTo(Money.of("220.00", "USD"));
        assertThat(hold.priceOf("12B")).isEqualTo(Money.of("110.00", "USD"));
        assertThat(hold.expiresAt()).isEqualTo(EXPIRES);
        server.verify(postRequestedFor(urlPathEqualTo("/api/v1/flights/7/holds"))
                .withHeader("Idempotency-Key", equalTo("bk-key"))
                .withRequestBody(equalToJson("{\"seatNumbers\":[\"12A\",\"12B\"]}")));
        assertThatThrownBy(() -> hold.priceOf("99Z")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void businessRejectionKeepsItsCodeAndIsNotRetried() {
        InventoryStubs.stubSeatUnavailable(server, 7L, "12A");

        assertThatThrownBy(() -> adapter.holdSeats(7L, List.of("12A"), "bk-key"))
                .isInstanceOfSatisfying(SkyBookException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(BookingErrorCode.SEAT_UNAVAILABLE);
                    assertThat(ex.getMessage()).contains("12A");
                    assertThat(ex.getProperties()).containsEntry("seatNumbers", List.of("12A"));
                });
        server.verify(exactly(1), postRequestedFor(urlPathEqualTo("/api/v1/flights/7/holds")));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void transientFailureIsRetriedTransparently() {
        server.stubFor(post(urlPathEqualTo("/api/v1/holds/h-1/confirmation")).inScenario("flaky")
                .whenScenarioStateIs(STARTED).willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        server.stubFor(post(urlPathEqualTo("/api/v1/holds/h-1/confirmation")).inScenario("flaky")
                .whenScenarioStateIs("recovered").willReturn(aResponse().withStatus(200)));

        adapter.confirmHold("h-1", "K7QX2M");

        server.verify(exactly(2), postRequestedFor(urlPathEqualTo("/api/v1/holds/h-1/confirmation")));
    }

    @Test
    void persistentOutageBecomesServiceUnavailableAndOpensTheCircuit() {
        server.stubFor(delete(urlPathEqualTo("/api/v1/holds/h-2")).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> adapter.releaseHold("h-2"))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.SERVICE_UNAVAILABLE);
        assertThatThrownBy(() -> adapter.releaseHold("h-2"))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.SERVICE_UNAVAILABLE);

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsSoFar = server.getAllServeEvents().size();
        assertThatThrownBy(() -> adapter.releaseHold("h-2"))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.SERVICE_UNAVAILABLE);
        assertThat(server.getAllServeEvents()).as("open circuit fails fast").hasSize(callsSoFar);
    }

    @Test
    void slowResponsesTimeOut() {
        server.stubFor(post(urlPathEqualTo("/api/v1/flights/7/seat-releases"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2_000)));

        assertThatThrownBy(() -> adapter.releaseBookedSeats(7L, "K7QX2M"))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void unknownCodesFallBackByStatus() {
        assertThat(InventoryRestAdapter.toBookingCode("HOLD_EXPIRED", 409)).isEqualTo(BookingErrorCode.HOLD_EXPIRED);
        assertThat(InventoryRestAdapter.toBookingCode("RESOURCE_NOT_FOUND", 404))
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
        assertThat(InventoryRestAdapter.toBookingCode(null, 404)).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
        assertThat(InventoryRestAdapter.toBookingCode("SOMETHING_NEW", 409)).isEqualTo(CommonErrorCode.CONFLICT);
        assertThat(InventoryRestAdapter.toBookingCode(null, 400)).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        assertThat(InventoryRestAdapter.toBookingCode(null, 418)).isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
    }

    @Test
    void nonJsonErrorBodiesAreHandled() {
        server.stubFor(post(urlPathEqualTo("/api/v1/flights/9/holds"))
                .willReturn(aResponse().withStatus(422).withBody("<html>nope</html>")));

        assertThatThrownBy(() -> adapter.holdSeats(9L, List.of("1A"), "k"))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.BUSINESS_RULE_VIOLATION)
                .hasMessageContaining("HTTP 422");
    }
}
