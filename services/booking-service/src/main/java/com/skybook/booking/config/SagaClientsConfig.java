package com.skybook.booking.config;

import java.net.http.HttpClient;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

import com.skybook.booking.adapter.out.messaging.OutboxBookingEventPublisher;
import com.skybook.booking.adapter.out.partner.CrmCustomerProfileAdapter;
import com.skybook.booking.adapter.out.partner.GdsTicketingAdapter;
import com.skybook.booking.adapter.out.partner.LoyaltyRestAdapter;
import com.skybook.booking.adapter.out.payment.PaymentRestAdapter;
import com.skybook.booking.adapter.out.support.RemoteCall;
import com.skybook.booking.application.port.out.CustomerProfilePort;
import com.skybook.booking.application.port.out.LoyaltyPort;
import com.skybook.booking.application.port.out.PaymentPort;
import com.skybook.booking.application.port.out.TicketingPort;
import com.skybook.messaging.DeadLetterSupport;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import tools.jackson.databind.ObjectMapper;

/** Wires payment-service and the partner systems (GDS, CRM, loyalty) to the saga's ports, plus Kafka topics. */
@Configuration(proxyBeanMethods = false)
public class SagaClientsConfig {

    private final ClientsProperties properties;
    private final RestClient.Builder builder;
    private final SslBundles sslBundles;
    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final BulkheadRegistry bulkheads;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("checkstyle:ParameterNumber")
    SagaClientsConfig(ClientsProperties properties, RestClient.Builder builder, SslBundles sslBundles,
                      CircuitBreakerRegistry circuitBreakers, RetryRegistry retries, BulkheadRegistry bulkheads,
                      ObjectMapper objectMapper) {
        this.properties = properties;
        this.builder = builder;
        this.sslBundles = sslBundles;
        this.circuitBreakers = circuitBreakers;
        this.retries = retries;
        this.bulkheads = bulkheads;
        this.objectMapper = objectMapper;
    }

    @Bean
    PaymentPort paymentPort(ObjectProvider<ClientRegistrationRepository> registrations,
                            ObjectProvider<OAuth2AuthorizedClientService> clientService) {
        return new PaymentRestAdapter(restClient("payment", registrations, clientService), remote("payment"));
    }

    @Bean
    TicketingPort ticketingPort() {
        return new GdsTicketingAdapter(restClient("gds", null, null), remote("gds"));
    }

    @Bean
    CustomerProfilePort customerProfilePort() {
        return new CrmCustomerProfileAdapter(restClient("crm", null, null), remote("crm"));
    }

    @Bean
    LoyaltyPort loyaltyPort() {
        return new LoyaltyRestAdapter(restClient("loyalty", null, null), remote("loyalty"));
    }

    @Bean
    NewTopic bookingEventsTopic(@Value("${skybook.kafka.partitions:3}") int partitions,
                                @Value("${skybook.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(OutboxBookingEventPublisher.TOPIC).partitions(partitions).replicas(replicas).build();
    }

    /** booking-service owns the dead-letter topic of its payment-events consumer. */
    @Bean
    NewTopic paymentEventsDeadLetterTopic(@Value("${skybook.kafka.partitions:3}") int partitions,
                                          @Value("${skybook.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(DeadLetterSupport.deadLetterTopic("skybook.payment.events", "booking-service"))
                .partitions(partitions).replicas(replicas).build();
    }

    private RemoteCall remote(String name) {
        return new RemoteCall(name, circuitBreakers.circuitBreaker(name), retries.retry(name),
                bulkheads.bulkhead(name), objectMapper);
    }

    private RestClient restClient(String name, ObjectProvider<ClientRegistrationRepository> registrations,
                                  ObjectProvider<OAuth2AuthorizedClientService> clientService) {
        ClientsProperties.Client client = properties.get(name);
        HttpClient.Builder http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(client.connectTimeout());
        if (client.sslBundle() != null && !client.sslBundle().isBlank()) {
            SslBundle bundle = sslBundles.getBundle(client.sslBundle());
            http.sslContext(bundle.createSslContext());
        }
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http.build());
        requestFactory.setReadTimeout(client.readTimeout());
        RestClient.Builder restClient = builder.clone().baseUrl(client.baseUrl()).requestFactory(requestFactory);
        String registrationId = client.oauth2RegistrationId();
        if (registrations != null && registrationId != null && !registrationId.isBlank()) {
            restClient.requestInterceptor(BookingConfig.clientCredentialsInterceptor(registrationId,
                    registrations.getObject(), clientService.getObject()));
        }
        return restClient.build();
    }
}
