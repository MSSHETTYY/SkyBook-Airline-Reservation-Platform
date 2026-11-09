package com.skybook.inventory.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.skybook.inventory.support.InventoryTestSupport;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;

/** Flight search v2 (ADR-0020) and the deprecation signals on v1. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FlightOffersApiTest extends InventoryTestSupport {

    @LocalServerPort
    private int port;

    private String day;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        // newFlight() departs ten days from the test clock.
        day = clock.instant().plus(Duration.ofDays(10)).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    private JsonPath search(Map<String, Object> params) {
        return given().queryParam("origin", "DXB").queryParam("destination", "LHR")
                .queryParams(params)
                .when().get("/api/v2/flights")
                .then().statusCode(200)
                .contentType("application/json")
                .extract().jsonPath();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> offer(JsonPath page, Long flightId) {
        List<Map<String, Object>> items = page.getList("items");
        return items.stream().filter(item -> ((Number) item.get("flightId")).longValue() == flightId)
                .findFirst().orElse(null);
    }

    @Test
    void returnsOffersPerCabinOverADateRange() {
        Long flightId = newFlight(3, "AB");
        LocalDate first = LocalDate.parse(day).minusDays(2);

        JsonPath page = search(Map.of("departureFrom", first.toString(), "departureTo", day, "size", 50));

        Map<String, Object> offer = offer(page, flightId);
        assertThat(offer).isNotNull();
        assertThat(offer.get("durationMinutes")).isEqualTo(420);
        assertThat(offer.get("cabins")).asInstanceOf(InstanceOfAssertFactories.LIST).hasSize(2);
        assertThat(page.getString("items.find { it.flightId == " + flightId + " }.cabins.find { it.cabinClass == "
                + "'BUSINESS' }.fromPrice.amount")).isEqualTo("250.0");
        assertThat(page.getInt("items.find { it.flightId == " + flightId + " }.cabins.find { it.cabinClass == "
                + "'ECONOMY' }.seatsAvailable")).isEqualTo(4);
        assertThat(page.getLong("totalElements")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void filtersByCabinAndBudgetAndSortsByPrice() {
        Long flightId = newFlight(3, "AB");

        // 2 business seats (row 1): 3 business passengers do not fit, 3 economy passengers do.
        assertThat(offer(search(Map.of("departureFrom", day, "cabin", "BUSINESS", "passengers", 3, "size", 50)),
                flightId)).isNull();
        assertThat(offer(search(Map.of("departureFrom", day, "cabin", "ECONOMY", "passengers", 3, "size", 50)),
                flightId)).isNotNull();

        // Business from 250: over a 150 budget in business, but economy (100) fits when no cabin is given.
        assertThat(offer(search(Map.of("departureFrom", day, "cabin", "BUSINESS", "maxPrice", 150, "size", 50)),
                flightId)).isNull();
        assertThat(offer(search(Map.of("departureFrom", day, "maxPrice", 150, "size", 50)), flightId)).isNotNull();

        List<Float> prices = search(Map.of("departureFrom", day, "sort", "price", "size", 50))
                .getList("items.collect { it.cabins.findAll { c -> c.fromPrice != null }"
                        + ".collect { c -> c.fromPrice.amount }.min() }", Float.class);
        assertThat(prices).isSorted();
    }

    @Test
    void pagesThroughResults() {
        newFlight(3, "AB");
        newFlight(3, "AB");

        given().queryParam("origin", "DXB").queryParam("destination", "LHR").queryParam("departureFrom", day)
                .queryParam("size", 1).queryParam("page", 1)
                .when().get("/api/v2/flights")
                .then().statusCode(200)
                .body("items", hasSize(1))
                .body("page", equalTo(1))
                .body("size", equalTo(1))
                .body("totalElements", greaterThanOrEqualTo(2));
    }

    @Test
    void rejectsInvalidRangesAndSortOrders() {
        LocalDate from = LocalDate.parse(day);
        given().queryParam("origin", "DXB").queryParam("destination", "LHR")
                .queryParam("departureFrom", from.toString()).queryParam("departureTo", from.plusDays(7).toString())
                .when().get("/api/v2/flights")
                .then().statusCode(400)
                .contentType("application/problem+json")
                .body("code", equalTo("VALIDATION_FAILED"))
                .body("detail", containsString("at most 7 days"));

        given().queryParam("origin", "DXB").queryParam("destination", "LHR")
                .queryParam("departureFrom", from.toString()).queryParam("departureTo", from.minusDays(1).toString())
                .when().get("/api/v2/flights")
                .then().statusCode(400)
                .body("detail", containsString("must not be before"));

        given().queryParam("origin", "DXB").queryParam("destination", "LHR").queryParam("departureFrom", day)
                .queryParam("sort", "CHEAPEST")
                .when().get("/api/v2/flights")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"));
    }

    @Test
    void versionOneAnnouncesItsSuccessor() {
        given().queryParam("origin", "DXB").queryParam("destination", "LHR").queryParam("date", day)
                .when().get("/api/v1/flights")
                .then().statusCode(200)
                .header("Deprecation", matchesPattern("@\\d+"))
                .header("Sunset", "Wed, 30 Jun 2027 23:59:59 GMT")
                .header("Link", "</api/v2/flights>; rel=\"successor-version\"");

        given().queryParam("origin", "DXB").queryParam("destination", "LHR").queryParam("departureFrom", day)
                .when().get("/api/v2/flights")
                .then().statusCode(200)
                .header("Deprecation", not(matchesPattern(".+")));
    }
}
