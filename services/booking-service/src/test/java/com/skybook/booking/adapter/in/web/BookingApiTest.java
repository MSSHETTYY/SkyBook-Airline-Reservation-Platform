package com.skybook.booking.adapter.in.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.skybook.booking.support.BookingTestSupport;
import com.skybook.booking.support.InventoryStubs;
import com.skybook.common.security.Roles;

import io.restassured.http.ContentType;

class BookingApiTest extends BookingTestSupport {

    private static final String REQUEST = """
            {"flightId":%d,"contactEmail":"ada@example.test","passengers":[
              {"firstName":"Ada","lastName":"Lovelace","dateOfBirth":"1990-12-10","seatNumber":"12A"},
              {"firstName":"Alan","lastName":"Turing","dateOfBirth":"1992-06-23","seatNumber":"12B"}]}
            """;

    private String createBooking(String customer, long flightId, String key) {
        InventoryStubs.stubHold(INVENTORY, flightId, START.plus(Duration.ofDays(10)),
                START.plus(Duration.ofMinutes(15)), List.of("12A", "12B"), "110.00");
        return given().header("Authorization", bearer(customer, Roles.CUSTOMER))
                .header("Idempotency-Key", key)
                .contentType(ContentType.JSON).body(REQUEST.formatted(flightId))
                .when().post("/api/v1/bookings")
                .then().statusCode(201)
                .extract().path("bookingReference");
    }

    @Test
    void customerCreatesABookingThatAwaitsPayment() {
        InventoryStubs.stubHold(INVENTORY, 7L, START.plus(Duration.ofDays(10)),
                START.plus(Duration.ofMinutes(15)), List.of("12A", "12B"), "110.00");

        given().header("Authorization", bearer("cust-a", Roles.CUSTOMER))
                .header("Idempotency-Key", "create-key-0001")
                .contentType(ContentType.JSON).body(REQUEST.formatted(7))
                .when().post("/api/v1/bookings")
                .then().statusCode(201)
                .header("Location", matchesPattern(".*/api/v1/bookings/[A-Z2-9]{6}"))
                .body("status", equalTo("PENDING_PAYMENT"))
                .body("totalPrice.amount", equalTo(220.0f))
                .body("flight.flightNumber", equalTo("SB101"))
                .body("passengers", hasSize(2))
                .body("holdExpiresAt", equalTo("2026-12-01T08:15:00Z"));
    }

    @Test
    void retryWithTheSameIdempotencyKeyReturnsTheSameBooking() {
        String first = createBooking("cust-b", 8L, "create-key-0002");
        String second = createBooking("cust-b", 8L, "create-key-0002");

        assertThat(second).isEqualTo(first);
    }

    @Test
    void customersCannotSeeOtherCustomersBookingsButStaffCan() {
        String reference = createBooking("cust-c", 9L, "create-key-0003");

        given().header("Authorization", bearer("cust-c", Roles.CUSTOMER))
                .when().get("/api/v1/bookings/{ref}", reference).then().statusCode(200)
                .body("bookingReference", equalTo(reference));
        given().header("Authorization", bearer("cust-intruder", Roles.CUSTOMER))
                .when().get("/api/v1/bookings/{ref}", reference).then().statusCode(404)
                .body("code", equalTo("RESOURCE_NOT_FOUND"));
        given().header("Authorization", bearer("agent-1", Roles.AGENT))
                .when().get("/api/v1/bookings/{ref}", reference).then().statusCode(200);
    }

    @Test
    void myBookingsListsOnlyTheCallersBookings() {
        String mine = createBooking("cust-d", 10L, "create-key-0004");
        createBooking("cust-e", 11L, "create-key-0005");

        given().header("Authorization", bearer("cust-d", Roles.CUSTOMER))
                .when().get("/api/v1/bookings")
                .then().statusCode(200)
                .body("items.bookingReference", equalTo(List.of(mine)))
                .body("totalElements", equalTo(1));
    }

    @Test
    void adminSearchIsForStaffOnly() {
        String reference = createBooking("cust-f", 12L, "create-key-0006");

        given().header("Authorization", bearer("cust-f", Roles.CUSTOMER))
                .when().get("/api/v1/admin/bookings").then().statusCode(403)
                .body("code", equalTo("ACCESS_DENIED"));
        given().header("Authorization", bearer("admin-1", Roles.ADMIN))
                .queryParam("flightId", 12).queryParam("status", "PENDING_PAYMENT")
                .when().get("/api/v1/admin/bookings").then().statusCode(200)
                .body("items.bookingReference", hasItem(reference));
    }

    @Test
    void staffCannotCreateBookingsOnBehalfOfThemselves() {
        given().header("Authorization", bearer("agent-2", Roles.AGENT))
                .header("Idempotency-Key", "create-key-0007")
                .contentType(ContentType.JSON).body(REQUEST.formatted(7))
                .when().post("/api/v1/bookings")
                .then().statusCode(403);
    }

    @Test
    void seatTakenInInventoryIsAConflictProblem() {
        InventoryStubs.stubSeatUnavailable(INVENTORY, 13L, "12A");

        given().header("Authorization", bearer("cust-g", Roles.CUSTOMER))
                .header("Idempotency-Key", "create-key-0008")
                .contentType(ContentType.JSON).body(REQUEST.formatted(13))
                .when().post("/api/v1/bookings")
                .then().statusCode(409)
                .contentType("application/problem+json")
                .body("code", equalTo("SEAT_UNAVAILABLE"))
                .body("seatNumbers", hasItem("12A"));
    }

    @Test
    void inventoryOutageIsAServiceUnavailableProblem() {
        INVENTORY.stubFor(post(urlPathEqualTo("/api/v1/flights/14/holds")).willReturn(aResponse().withStatus(503)));

        given().header("Authorization", bearer("cust-h", Roles.CUSTOMER))
                .header("Idempotency-Key", "create-key-0009")
                .contentType(ContentType.JSON).body(REQUEST.formatted(14))
                .when().post("/api/v1/bookings")
                .then().statusCode(503)
                .body("code", equalTo("SERVICE_UNAVAILABLE"))
                .body("detail", not(equalTo(null)));
    }

    @Test
    void requestsWithoutTokenOrWithInvalidDataAreRejected() {
        given().when().get("/api/v1/bookings").then().statusCode(401).body("code", equalTo("UNAUTHENTICATED"));

        given().header("Authorization", bearer("cust-i", Roles.CUSTOMER))
                .header("Idempotency-Key", "create-key-0010")
                .contentType(ContentType.JSON)
                .body("{\"flightId\":7,\"contactEmail\":\"not-an-email\",\"passengers\":[]}")
                .when().post("/api/v1/bookings")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"))
                .body("errors.field", hasItem("contactEmail"))
                .body("correlationId", notNullValue());

        given().header("Authorization", bearer("cust-i", Roles.CUSTOMER))
                .contentType(ContentType.JSON).body(REQUEST.formatted(7))
                .when().post("/api/v1/bookings")
                .then().statusCode(400);

        given().header("Authorization", bearer("cust-i", Roles.CUSTOMER))
                .when().get("/api/v1/bookings/{ref}", "bad").then().statusCode(400);
        given().header("Authorization", bearer("cust-i", Roles.CUSTOMER))
                .when().get("/api/v1/bookings/{ref}", "ZZZZZZ").then().statusCode(404);
    }
}
