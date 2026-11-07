package com.skybook.inventory.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import java.time.Duration;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.skybook.common.security.Roles;
import com.skybook.inventory.support.InventoryTestSupport;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;

/** HTTP contract tests: the generated interfaces, validation and RFC 9457 errors over a real server. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryApiTest extends InventoryTestSupport {

    private static final String SERVICE = bearer("booking-service", Roles.SERVICE);
    private static final String ADMIN = bearer("admin-1", Roles.ADMIN);

    @LocalServerPort
    private int port;

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
    }

    @Test
    void searchAndSeatMap() {
        Long flightId = newFlight(3, "AB");
        String date = clock.instant().plus(Duration.ofDays(10)).atZone(ZoneOffset.UTC).toLocalDate().toString();

        given().queryParam("origin", "DXB").queryParam("destination", "LHR").queryParam("date", date)
                .when().get("/api/v1/flights")
                .then().statusCode(200)
                .body("flightId", hasItem(flightId.intValue()));

        given().when().get("/api/v1/flights/{id}/seats", flightId)
                .then().statusCode(200)
                .body("seats", hasSize(6))
                .body("seats[0].seatNumber", equalTo("1A"))
                .body("seats[0].cabinClass", equalTo("BUSINESS"))
                .body("seats[0].price.currency", equalTo("USD"));
    }

    @Test
    void holdLifecycleOverHttp() {
        Long flightId = newFlight(3, "AB");

        String holdId = given().header("Authorization", SERVICE).contentType(ContentType.JSON)
                .header("Idempotency-Key", "api-hold-0001")
                .body("{\"seatNumbers\":[\"2A\"]}")
                .when().post("/api/v1/flights/{id}/holds", flightId)
                .then().statusCode(201)
                .header("Location", containsString("/api/v1/holds/"))
                .body("status", equalTo("ACTIVE"))
                .body("totalPrice.amount", equalTo(100.0f))
                .extract().path("holdId");

        given().header("Authorization", SERVICE).contentType(ContentType.JSON)
                .header("Idempotency-Key", "api-hold-0002")
                .body("{\"seatNumbers\":[\"2A\"]}")
                .when().post("/api/v1/flights/{id}/holds", flightId)
                .then().statusCode(409)
                .contentType("application/problem+json")
                .body("code", equalTo("SEAT_UNAVAILABLE"))
                .body("seatNumbers", hasItem("2A"));

        given().header("Authorization", SERVICE).contentType(ContentType.JSON)
                .body("{\"bookingReference\":\"K7QX2M\"}")
                .when().post("/api/v1/holds/{id}/confirmation", holdId)
                .then().statusCode(200)
                .body("status", equalTo("CONFIRMED"));

        given().header("Authorization", SERVICE).contentType(ContentType.JSON)
                .body("{\"bookingReference\":\"K7QX2M\"}")
                .when().post("/api/v1/flights/{id}/seat-releases", flightId)
                .then().statusCode(200)
                .body("releasedSeats", hasItem("2A"));
    }

    @Test
    void releaseIsIdempotentOverHttp() {
        Long flightId = newFlight(2, "AB");
        String holdId = given().header("Authorization", SERVICE).contentType(ContentType.JSON)
                .header("Idempotency-Key", "api-hold-0003")
                .body("{\"seatNumbers\":[\"1B\"]}")
                .when().post("/api/v1/flights/{id}/holds", flightId)
                .then().statusCode(201).extract().path("holdId");

        given().header("Authorization", SERVICE).when().delete("/api/v1/holds/{id}", holdId)
                .then().statusCode(204);
        given().header("Authorization", SERVICE).when().delete("/api/v1/holds/{id}", holdId)
                .then().statusCode(204);
        given().header("Authorization", SERVICE).when().get("/api/v1/holds/{id}", holdId)
                .then().statusCode(200).body("status", equalTo("RELEASED"));
    }

    @Test
    void holdsAreAnInternalApi() {
        Long flightId = newFlight(2, "AB");
        String body = "{\"seatNumbers\":[\"1A\"]}";

        given().contentType(ContentType.JSON).header("Idempotency-Key", "api-hold-0100").body(body)
                .when().post("/api/v1/flights/{id}/holds", flightId)
                .then().statusCode(401).body("code", equalTo("UNAUTHENTICATED"));
        given().header("Authorization", bearer("cust-1", Roles.CUSTOMER)).contentType(ContentType.JSON)
                .header("Idempotency-Key", "api-hold-0101").body(body)
                .when().post("/api/v1/flights/{id}/holds", flightId)
                .then().statusCode(403).body("code", equalTo("ACCESS_DENIED"));
    }

    @Test
    void invalidRequestsAreRfc9457Problems() {
        Long flightId = newFlight(2, "AB");

        given().header("Authorization", SERVICE).contentType(ContentType.JSON)
                .body("{\"seatNumbers\":[\"1A\"]}")
                .when().post("/api/v1/flights/{id}/holds", flightId)
                .then().statusCode(400)
                .contentType("application/problem+json")
                .body("correlationId", notNullValue());

        given().header("Authorization", SERVICE).contentType(ContentType.JSON)
                .header("Idempotency-Key", "api-hold-0004")
                .body("{\"seatNumbers\":[\"not-a-seat\"]}")
                .when().post("/api/v1/flights/{id}/holds", flightId)
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"));

        given().header("X-Correlation-Id", "trace-me-123")
                .when().get("/api/v1/flights/{id}", 987_654)
                .then().statusCode(404)
                .header("X-Correlation-Id", "trace-me-123")
                .body("code", equalTo("RESOURCE_NOT_FOUND"))
                .body("correlationId", equalTo("trace-me-123"));
    }

    @Test
    void adminCanCreateListAndCancelFlights() {
        String departure = clock.instant().plus(Duration.ofDays(40)).toString();
        String arrival = clock.instant().plus(Duration.ofDays(40)).plus(Duration.ofHours(3)).toString();
        String body = """
                {"flightNumber":"SB777","origin":"DXB","destination":"BOM","departureTime":"%s","arrivalTime":"%s",
                 "aircraftType":"A320","baseFare":{"amount":199.00,"currency":"USD"},
                 "layout":{"rows":4,"seatLetters":"ABCD","businessRows":1}}
                """.formatted(departure, arrival);

        given().header("Authorization", bearer("agent-1", Roles.AGENT)).contentType(ContentType.JSON)
                .header("Idempotency-Key", "api-flight-0000").body(body)
                .when().post("/api/v1/admin/flights")
                .then().statusCode(403).body("code", equalTo("ACCESS_DENIED"));

        Integer flightId = given().header("Authorization", ADMIN).contentType(ContentType.JSON)
                .header("Idempotency-Key", "api-flight-0001")
                .body(body)
                .when().post("/api/v1/admin/flights")
                .then().statusCode(201)
                .body("availableSeats.economy", equalTo(12))
                .body("availableSeats.business", equalTo(4))
                .extract().path("flightId");

        given().header("Authorization", bearer("agent-1", Roles.AGENT)).queryParam("size", 100)
                .when().get("/api/v1/admin/flights")
                .then().statusCode(200).body("items.flightId", hasItem(flightId));

        given().header("Authorization", ADMIN)
                .when().post("/api/v1/admin/flights/{id}/cancellation", flightId)
                .then().statusCode(200).body("status", equalTo("CANCELLED"));
    }
}
