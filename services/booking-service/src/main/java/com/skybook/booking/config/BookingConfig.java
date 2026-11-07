package com.skybook.booking.config;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.util.random.RandomGenerator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import com.skybook.booking.adapter.out.inventory.InventoryRestAdapter;
import com.skybook.booking.application.port.out.InventoryPort;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import tools.jackson.databind.ObjectMapper;

/** Wires the driven adapters to the application ports. */
@Configuration(proxyBeanMethods = false)
public class BookingConfig {

    /** Booking references must not be guessable: SecureRandom, not java.util.Random. */
    @Bean
    RandomGenerator bookingReferenceRandom() {
        return new SecureRandom();
    }

    @Bean
    InventoryPort inventoryPort(RestClient.Builder builder, InventoryClientProperties properties,
                                ObjectProvider<ClientRegistrationRepository> registrations,
                                ObjectProvider<OAuth2AuthorizedClientService> clientService,
                                CircuitBreakerRegistry circuitBreakers, RetryRegistry retries,
                                ObjectMapper objectMapper, SslBundles sslBundles) {
        String bundleName = properties.sslBundle();
        SslBundle sslBundle = bundleName == null || bundleName.isBlank() ? null : sslBundles.getBundle(bundleName);
        RestClient.Builder client = builder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(properties, sslBundle));
        String registrationId = properties.oauth2RegistrationId();
        if (registrationId != null && !registrationId.isBlank()) {
            client.requestInterceptor(clientCredentialsInterceptor(registrationId, registrations.getObject(),
                    clientService.getObject()));
        }
        return new InventoryRestAdapter(client.build(), circuitBreakers.circuitBreaker("inventory"),
                retries.retry("inventory"), objectMapper);
    }

    /**
     * JDK HTTP client with timeouts and, when a bundle is given, mutual TLS: the bundle's key store holds the
     * client certificate presented to inventory, its trust store the CA that inventory's certificate must
     * chain to. Host names are verified against the certificate's subject alternative names.
     */
    public static JdkClientHttpRequestFactory requestFactory(InventoryClientProperties properties,
                                                             SslBundle sslBundle) {
        HttpClient.Builder http = HttpClient.newBuilder()
                // HTTP/1.1: plain-HTTP h2c upgrades are poorly supported by some servers.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout());
        if (sslBundle != null) {
            http.sslContext(sslBundle.createSslContext());
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    /**
     * Adds a client-credentials bearer token (the booking service identity, role SERVICE) to every call.
     * Uses the service-scoped manager so it also works outside HTTP requests (schedulers, Kafka listeners).
     */
    static OAuth2ClientHttpRequestInterceptor clientCredentialsInterceptor(
            String registrationId, ClientRegistrationRepository registrations, OAuth2AuthorizedClientService service) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, service);
        manager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        var interceptor = new OAuth2ClientHttpRequestInterceptor(manager);
        interceptor.setClientRegistrationIdResolver(request -> registrationId);
        var servicePrincipal = new AnonymousAuthenticationToken("booking-service", "booking-service",
                AuthorityUtils.createAuthorityList("ROLE_SERVICE"));
        interceptor.setPrincipalResolver(request -> servicePrincipal);
        return interceptor;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "skybook.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
