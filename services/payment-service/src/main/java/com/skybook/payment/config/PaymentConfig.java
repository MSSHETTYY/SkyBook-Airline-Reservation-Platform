package com.skybook.payment.config;

import java.time.Clock;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.skybook.payment.provider.FakePaymentProvider;
import com.skybook.payment.provider.PaymentProvider;
import com.skybook.payment.provider.ResilientPaymentProvider;
import com.skybook.payment.provider.StripePaymentProvider;
import com.skybook.payment.service.PaymentEvents;
import com.skybook.payment.service.PaymentService;
import com.skybook.payment.service.PaymentSimulationService;
import com.skybook.payment.service.StripeWebhookService;
import com.stripe.StripeClient;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;

@Configuration(proxyBeanMethods = false)
public class PaymentConfig {

    /** The provider (Stripe or fake) is always wrapped in bulkhead + circuit breaker + retry. */
    @Bean
    PaymentProvider paymentProvider(PaymentProperties properties, BulkheadRegistry bulkheads,
                                    CircuitBreakerRegistry circuitBreakers, RetryRegistry retries) {
        PaymentProvider base = properties.fake() ? new FakePaymentProvider() : stripe(properties);
        return new ResilientPaymentProvider(base, bulkheads.bulkhead("payment-provider"),
                circuitBreakers.circuitBreaker("payment-provider"), retries.retry("payment-provider"));
    }

    private static PaymentProvider stripe(PaymentProperties properties) {
        StripeClient.StripeClientBuilder builder = StripeClient.builder()
                .setApiKey(properties.stripeSecretKey())
                .setConnectTimeout(2_000)
                .setReadTimeout(10_000)
                // Our Resilience4j retry owns retrying; the SDK must not retry on top of it.
                .setMaxNetworkRetries(0);
        if (properties.stripeApiBase() != null && !properties.stripeApiBase().isBlank()) {
            builder.setApiBase(properties.stripeApiBase());
        }
        return new StripePaymentProvider(builder.build());
    }

    @Bean
    @ConditionalOnProperty(name = "skybook.payment.provider", havingValue = "fake", matchIfMissing = true)
    PaymentSimulationService paymentSimulationService(PaymentService payments, StripeWebhookService webhooks,
                                                      PaymentProperties properties, Clock clock) {
        return new PaymentSimulationService(payments, webhooks, properties, clock);
    }

    @Bean
    NewTopic paymentEventsTopic(@Value("${skybook.kafka.partitions:3}") int partitions,
                                @Value("${skybook.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(PaymentEvents.TOPIC).partitions(partitions).replicas(replicas).build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "skybook.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
