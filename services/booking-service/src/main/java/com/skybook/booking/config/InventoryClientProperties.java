package com.skybook.booking.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for inventory-service ({@code skybook.booking.inventory.*}).
 *
 * @param baseUrl               inventory-service base URL
 * @param connectTimeout        TCP connect timeout
 * @param readTimeout           response timeout per attempt
 * @param oauth2RegistrationId  Spring Security client registration used for client-credentials tokens;
 *                              blank disables token propagation (tests against WireMock)
 * @param sslBundle             SSL bundle with the client certificate for mutual TLS; blank for plain HTTP
 */
@ConfigurationProperties("skybook.booking.inventory")
public record InventoryClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout,
                                        String oauth2RegistrationId, String sslBundle) {

    public InventoryClientProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
    }
}
