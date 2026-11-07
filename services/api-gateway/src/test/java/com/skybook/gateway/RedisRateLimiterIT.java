package com.skybook.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

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

        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            statuses.add(client.get().uri("/api/v1/flights/1").exchange().returnResult(String.class)
                    .getStatus().value());
        }

        assertThat(statuses).contains(200, 429);
    }
}
