package com.skybook.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.redis.testcontainers.RedisContainer;

/** The production rate limiter (Redis token buckets shared by all gateway replicas) against real Redis. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "skybook.gateway.rate-limiter-bean=redisRateLimiter")
class RedisRateLimiterIT {

    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:8.10.3-alpine"));

    private static final WireMockServer BACKEND = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        BACKEND.start();
    }

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        registry.add("skybook.gateway.inventory-uri", BACKEND::baseUrl);
    }

    @Test
    void anonymousBurstBeyondCapacityIsThrottled() {
        BACKEND.stubFor(get(urlPathEqualTo("/api/v1/flights/1")).willReturn(okJson("{}")));
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

        // Warm up (connection pool, route, Redis script) so the burst below is not slowed by first-call costs.
        assertThat(client.get().uri("/api/v1/flights/1").exchange().returnResult(String.class).getStatus().value())
                .isEqualTo(200);

        // A genuinely simultaneous burst of 100 requests against a bucket of 40 (refilling 20/s). Sequential calls
        // could be slow enough on a CI runner for the bucket to refill between them, which made this test flaky.
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/flights/1"))
                .timeout(Duration.ofSeconds(20)).build();
        List<CompletableFuture<HttpResponse<Void>>> burst = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            burst.add(http.sendAsync(request, HttpResponse.BodyHandlers.discarding()));
        }
        List<Integer> statuses = burst.stream().map(CompletableFuture::join).map(HttpResponse::statusCode).toList();

        assertThat(statuses).contains(200, 429);
    }
}
