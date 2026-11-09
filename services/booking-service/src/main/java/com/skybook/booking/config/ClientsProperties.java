package com.skybook.booking.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound clients other than inventory ({@code skybook.booking.clients.<name>.*}): payment, gds, crm, loyalty.
 *
 * @param clients settings per client name
 */
@ConfigurationProperties("skybook.booking")
public record ClientsProperties(Map<String, Client> clients) {

    public ClientsProperties {
        clients = clients == null ? Map.of() : Map.copyOf(clients);
    }

    public Client get(String name) {
        Client client = clients.get(name);
        if (client == null || client.baseUrl() == null || client.baseUrl().isBlank()) {
            throw new IllegalStateException("skybook.booking.clients." + name + ".base-url must be set");
        }
        return client;
    }

    /**
     * Settings of one client.
     *
     * @param baseUrl              base URL of the service or partner
     * @param connectTimeout       TCP connect timeout
     * @param readTimeout          response timeout per attempt
     * @param oauth2RegistrationId client registration for client-credentials tokens (internal services only)
     * @param sslBundle            SSL bundle for mutual TLS; blank for plain HTTP
     */
    public record Client(String baseUrl, Duration connectTimeout, Duration readTimeout, String oauth2RegistrationId,
                         String sslBundle) {

        public Client {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
        }
    }
}
