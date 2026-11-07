package com.skybook.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.skybook.common.test.TestCertificates;
import com.skybook.inventory.support.InventoryTestSupport;

/**
 * The "mtls" profile end to end: the API port only accepts callers with a certificate from the SkyBook
 * CA, while health probes stay reachable over plain HTTP on the management port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "SKYBOOK_TLS_CERT=" + TestCertificates.SERVICE_CERT,
        "SKYBOOK_TLS_KEY=" + TestCertificates.SERVICE_KEY,
        "SKYBOOK_TLS_CA=" + TestCertificates.CA,
        "management.server.port=0",
        // Hot reload watches files; the test certificates are classpath resources.
        "spring.ssl.bundle.pem.skybook-tls.reload-on-update=false"
})
@ActiveProfiles({"test", "mtls"})
class MutualTlsTest extends InventoryTestSupport {

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    private HttpResponse<String> get(SslBundle clientBundle, String path) throws Exception {
        HttpClient client = HttpClient.newBuilder().sslContext(TestCertificates.sslContext(clientBundle)).build();
        return client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void serviceWithATrustedCertificateIsServed() throws Exception {
        Long flightId = newFlight(2, "AB");

        HttpResponse<String> response = get(TestCertificates.trustedClient(), "/api/v1/flights/" + flightId);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"flightId\":" + flightId);
    }

    @Test
    void callerWithoutAClientCertificateIsRejectedDuringTheHandshake() {
        assertThatThrownBy(() -> get(TestCertificates.anonymousClient(), "/api/v1/flights/1"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void certificateFromAnUntrustedCaIsRejected() {
        assertThatThrownBy(() -> get(TestCertificates.rogueClient(), "/api/v1/flights/1"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void plainHttpIsNotServedOnTheApiPort() throws Exception {
        HttpClient plain = HttpClient.newHttpClient();
        try {
            HttpResponse<String> response = plain.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/api/v1/flights/1")).build(), HttpResponse.BodyHandlers.ofString());
            // Tomcat answers plaintext requests on a TLS port with "400: this port requires TLS".
            assertThat(response.statusCode()).isEqualTo(400);
        } catch (IOException expected) {
            assertThat(expected).isNotNull();
        }
    }

    @Test
    void healthProbesUsePlainHttpOnTheManagementPort() throws Exception {
        HttpResponse<String> health = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/health/liveness")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");
    }
}
