package com.skybook.booking.adapter.out.inventory;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.skybook.booking.config.BookingConfig;
import com.skybook.booking.config.InventoryClientProperties;
import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.test.TestCertificates;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import tools.jackson.databind.json.JsonMapper;

/** The inventory client presents its certificate to an HTTPS server that requires client authentication. */
class InventoryMutualTlsTest {

    private static WireMockServer server;

    @BeforeAll
    static void startHttpsServerRequiringClientCertificates() {
        server = new WireMockServer(WireMockConfiguration.options()
                .httpDisabled(true)
                .httpsPort(0)
                .keystorePath(TestCertificates.serviceKeyStoreFile().toString())
                .keystorePassword(TestCertificates.STORE_PASSWORD)
                .keyManagerPassword(TestCertificates.STORE_PASSWORD)
                .keystoreType("PKCS12")
                .needClientAuth(true)
                .trustStorePath(TestCertificates.trustStoreFile().toString())
                .trustStorePassword(TestCertificates.STORE_PASSWORD)
                .trustStoreType("PKCS12"));
        server.start();
        server.stubFor(delete(urlPathEqualTo("/api/v1/holds/h-1")).willReturn(aResponse().withStatus(204)));
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    private InventoryRestAdapter adapterWith(SslBundle bundle) {
        InventoryClientProperties properties = new InventoryClientProperties("https://localhost:" + server.httpsPort(),
                Duration.ofSeconds(2), Duration.ofSeconds(2), "", "skybook-tls");
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(BookingConfig.requestFactory(properties, bundle))
                .build();
        return new InventoryRestAdapter(restClient, CircuitBreaker.ofDefaults("inventory"),
                Retry.of("inventory", RetryConfig.custom().maxAttempts(1).build()), JsonMapper.builder().build());
    }

    @Test
    void callSucceedsWithTheServiceCertificate() {
        assertThatCode(() -> adapterWith(TestCertificates.trustedClient()).releaseHold("h-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void callWithoutAClientCertificateIsRefused() {
        assertThatThrownBy(() -> adapterWith(TestCertificates.anonymousClient()).releaseHold("h-1"))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.SERVICE_UNAVAILABLE);
    }
}
