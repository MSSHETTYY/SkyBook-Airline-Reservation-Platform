package com.skybook.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.skybook.common.security.Roles;
import com.skybook.common.test.TestJwtIssuer;

/** Edge behaviour end to end: routing, JWT, correlation IDs, rate limiting, CORS and circuit breaking. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "skybook.gateway.rate-limiter-bean=localRateLimiter",
        "skybook.gateway.local-replenish-rate=1",
        "skybook.gateway.local-burst-capacity=30",
        "skybook.gateway.payment-uri=http://localhost:1"
})
@Import(GatewayEdgeTest.TestBeans.class)
class GatewayEdgeTest {

    private static final TestJwtIssuer JWT = new TestJwtIssuer();
    private static final WireMockServer BACKEND = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        BACKEND.start();
    }

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return JWT.reactiveDecoder();
        }
    }

    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        registry.add("skybook.gateway.inventory-uri", BACKEND::baseUrl);
        registry.add("skybook.gateway.booking-uri", BACKEND::baseUrl);
    }

    @BeforeEach
    void setUp() {
        BACKEND.resetAll();
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private static String bearer(String subject, String... roles) {
        return "Bearer " + JWT.token(subject, roles);
    }

    @Test
    void anonymousSearchIsProxiedAndCorrelated() {
        BACKEND.stubFor(get(urlPathEqualTo("/api/v1/flights")).willReturn(okJson("[]")));

        String correlationId = client.get().uri("/api/v1/flights?origin=DXB&destination=LHR&date=2026-12-11")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Correlation-Id")
                .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");

        BACKEND.verify(getRequestedFor(urlPathEqualTo("/api/v1/flights"))
                .withHeader("X-Correlation-Id", equalTo(correlationId))
                .withQueryParam("origin", equalTo("DXB")));
    }

    @Test
    void validClientCorrelationIdIsKeptAndInvalidOneReplaced() {
        BACKEND.stubFor(get(urlPathEqualTo("/api/v1/flights/7")).willReturn(okJson("{}")));

        client.get().uri("/api/v1/flights/7").header("X-Correlation-Id", "client-trace-1").exchange()
                .expectHeader().valueEquals("X-Correlation-Id", "client-trace-1");
        client.get().uri("/api/v1/flights/7").header("X-Correlation-Id", "bad value\twith tab").exchange()
                .expectHeader().value("X-Correlation-Id", value -> assertThat(value).matches("[0-9a-f-]{36}"));
    }

    @Test
    void protectedRoutesRequireAValidToken() {
        client.get().uri("/api/v1/bookings").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
                .jsonPath("$.correlationId").isNotEmpty();

        String forged = "Bearer " + new TestJwtIssuer().token("mallory", Roles.ADMIN);
        client.get().uri("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, forged).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void tokenIsRelayedAndCookiesAreStripped() {
        BACKEND.stubFor(get(urlPathEqualTo("/api/v1/bookings")).willReturn(okJson("{\"items\":[]}")));
        String token = bearer("cust-1", Roles.CUSTOMER);

        client.get().uri("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, token)
                .header(HttpHeaders.COOKIE, "session=abc").exchange()
                .expectStatus().isOk();

        BACKEND.verify(getRequestedFor(urlPathEqualTo("/api/v1/bookings"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(token))
                .withHeader(HttpHeaders.COOKIE, absent())
                .withHeader("X-Correlation-Id", matching(".+")));
    }

    @Test
    void internalInventoryApisAreNotExposed() {
        client.post().uri("/api/v1/flights/7/holds").header(HttpHeaders.AUTHORIZATION, bearer("svc", Roles.SERVICE))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"seatNumbers\":[\"1A\"]}")
                .exchange()
                .expectStatus().isNotFound();
        assertThat(BACKEND.getAllServeEvents()).isEmpty();
    }

    @Test
    void failingBackendTriggersAProblemFallback() {
        BACKEND.stubFor(get(urlPathEqualTo("/api/v1/admin/flights")).willReturn(aResponse().withStatus(503)));

        client.get().uri("/api/v1/admin/flights").header(HttpHeaders.AUTHORIZATION, bearer("admin", Roles.ADMIN))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals("Retry-After", "10")
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void unreachableServiceTriggersTheFallbackToo() {
        client.get().uri("/api/v1/payments/anything").header(HttpHeaders.AUTHORIZATION, bearer("c", Roles.CUSTOMER))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void rateLimitIsPerUser() {
        BACKEND.stubFor(get(urlPathEqualTo("/api/v1/bookings")).willReturn(okJson("{\"items\":[]}")));
        String greedy = bearer("greedy-user", Roles.CUSTOMER);

        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            statuses.add(client.get().uri("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, greedy)
                    .exchange().returnResult(String.class).getStatus().value());
        }

        assertThat(statuses).contains(429);
        assertThat(statuses.subList(0, 25)).containsOnly(200);
        client.get().uri("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, bearer("patient-user", Roles.CUSTOMER))
                .exchange().expectStatus().isOk();
    }

    @Test
    void corsAllowsTheSpaOriginOnly() {
        client.method(HttpMethod.OPTIONS).uri("/api/v1/bookings")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Idempotency-Key")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");

        client.method(HttpMethod.OPTIONS).uri("/api/v1/bookings")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isForbidden();
    }
}
