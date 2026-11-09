package com.skybook.booking.adapter.out.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.skybook.booking.adapter.out.inventory.InventoryRestAdapter;
import com.skybook.booking.application.port.out.InventoryPort;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumer-driven contract with inventory-service for holding seats. The flight id is created by the
 * provider's state handler and injected into the request path ({@code fromProviderState}), because
 * inventory assigns flight ids itself.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "inventory-service")
class InventoryServiceContractTest {

    @Pact(consumer = "booking-service")
    V4Pact holdSeats(PactDslWithProvider builder) {
        return builder
                .given("a scheduled flight with seats 2A and 2B available")
                .uponReceiving("a request to hold two seats")
                .pathFromProviderState("/api/v1/flights/${flightId}/holds", "/api/v1/flights/1/holds")
                .method("POST")
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .matchHeader("Idempotency-Key", "[A-Za-z0-9._:-]{8,64}", "bk-contract-key-0001")
                .body(new PactDslJsonBody().array("seatNumbers").stringValue("2A").stringValue("2B").closeArray())
                .willRespondWith()
                .status(201)
                .body(holdResponse())
                .toPact(V4Pact.class);
    }

    private static PactDslJsonBody holdResponse() {
        PactDslJsonBody body = new PactDslJsonBody()
                .uuid("holdId")
                .integerType("flightId", 1L)
                .stringType("flightNumber", "SB101")
                .stringType("origin", "DXB")
                .stringType("destination", "LHR")
                .stringType("departureTime", "2026-12-11T08:00:00Z")
                .stringType("expiresAt", "2026-12-01T08:15:00Z")
                .stringValue("status", "ACTIVE");
        body.minArrayLike("seats", 2)
                .stringType("seatNumber", "2A")
                .object("price").decimalType("amount", 100.00).stringType("currency", "USD").closeObject()
                .closeObject()
                .closeArray();
        body.object("totalPrice").decimalType("amount", 200.00).stringType("currency", "USD").closeObject();
        return body;
    }

    @Test
    @PactTestFor(pactMethod = "holdSeats")
    void holdSeats(MockServer server) {
        RestClient client = RestClient.builder().baseUrl(server.getUrl())
                .requestFactory(new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
                .build();
        InventoryRestAdapter adapter = new InventoryRestAdapter(client, CircuitBreaker.ofDefaults("i"),
                Retry.ofDefaults("i"), JsonMapper.builder().build());

        InventoryPort.SeatHold hold = adapter.holdSeats(1L, List.of("2A", "2B"), "bk-contract-key-0001");

        assertThat(hold.seats()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(hold.total().isPositive()).isTrue();
    }
}
