package com.skybook.booking.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.github.tomakehurst.wiremock.WireMockServer;

/** WireMock stubs for the inventory-service contract (shared by adapter and API tests). */
public final class InventoryStubs {

    private InventoryStubs() {
    }

    public static String holdJson(String holdId, long flightId, Instant departure, Instant expiresAt,
                                  List<String> seats, String pricePerSeat) {
        String seatJson = seats.stream()
                .map(seat -> """
                        {"seatNumber":"%s","cabinClass":"ECONOMY","price":{"amount":%s,"currency":"USD"}}"""
                        .formatted(seat, pricePerSeat))
                .collect(Collectors.joining(","));
        double total = Double.parseDouble(pricePerSeat) * seats.size();
        return """
                {"holdId":"%s","flightId":%d,"flightNumber":"SB101","origin":"DXB","destination":"LHR",
                 "departureTime":"%s","status":"ACTIVE","seats":[%s],
                 "totalPrice":{"amount":%.2f,"currency":"USD"},"expiresAt":"%s"}
                """.formatted(holdId, flightId, departure, seatJson, total, expiresAt);
    }

    public static String stubHold(WireMockServer server, long flightId, Instant departure, Instant expiresAt,
                                  List<String> seats, String pricePerSeat) {
        String holdId = UUID.randomUUID().toString();
        server.stubFor(post(urlPathEqualTo("/api/v1/flights/" + flightId + "/holds"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody(holdJson(holdId, flightId, departure, expiresAt, seats, pricePerSeat))));
        server.stubFor(delete(urlPathMatching("/api/v1/holds/.*")).willReturn(aResponse().withStatus(204)));
        return holdId;
    }

    /** Stubs payment creation, hold confirmation, GDS ticketing and loyalty for a full happy-path saga. */
    public static void stubSagaPartners(WireMockServer server, String paymentId, List<String> tickets) {
        server.stubFor(post(urlPathEqualTo("/api/v1/payments")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"paymentId":"%s","bookingReference":"AAAAAA","purpose":"BOOKING","status":"REQUIRES_PAYMENT",
                         "amount":{"amount":220.00,"currency":"USD"},"amountRefunded":{"amount":0,"currency":"USD"},
                         "clientSecret":"%s_secret","createdAt":"2026-12-01T08:00:00Z"}"""
                        .formatted(paymentId, paymentId))));
        server.stubFor(post(urlPathMatching("/api/v1/holds/.*/confirmation")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
        server.stubFor(post(urlPathEqualTo("/gds/v1/tickets")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ticketNumbers\":[\"" + String.join("\",\"", tickets) + "\"]}")));
        server.stubFor(post(urlPathEqualTo("/loyalty/v1/accruals")).willReturn(aResponse().withStatus(202)));
    }

    public static void stubSeatUnavailable(WireMockServer server, long flightId, String seat) {
        server.stubFor(post(urlPathEqualTo("/api/v1/flights/" + flightId + "/holds"))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/problem+json")
                        .withBody("""
                                {"type":"urn:skybook:problem:seat-unavailable","title":"Seat no longer available",
                                 "status":409,"detail":"Seats no longer available: %s","code":"SEAT_UNAVAILABLE",
                                 "seatNumbers":["%s"]}
                                """.formatted(seat, seat))));
    }
}
