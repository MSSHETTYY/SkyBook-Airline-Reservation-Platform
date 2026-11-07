package com.skybook.payment.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.money.Money;
import com.skybook.payment.domain.PaymentErrorCode;
import com.skybook.payment.domain.RefundStatus;
import com.stripe.StripeClient;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/** The Stripe adapter against a WireMock stand-in for api.stripe.com: wire format, idempotency, error mapping. */
class StripePaymentProviderTest {

    private static WireMockServer stripe;
    private StripePaymentProvider provider;

    @BeforeAll
    static void start() {
        stripe = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        stripe.start();
    }

    @AfterAll
    static void stop() {
        stripe.stop();
    }

    @BeforeEach
    void setUp() {
        stripe.resetAll();
        provider = new StripePaymentProvider(StripeClient.builder().setApiKey("sk_test_dummy")
                .setApiBase(stripe.baseUrl()).setMaxNetworkRetries(0).build());
    }

    @Test
    void createsAPaymentIntentInMinorUnitsWithAnIdempotencyKey() {
        stripe.stubFor(post(urlPathEqualTo("/v1/payment_intents")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"pi_123\",\"object\":\"payment_intent\",\"client_secret\":\"pi_123_secret_abc\","
                        + "\"status\":\"requires_payment_method\"}")));

        PaymentProvider.ProviderIntent intent = provider.createIntent(Money.of("249.50", "USD"),
                Map.of("bookingReference", "K7QX2M"), "pay-key-1");

        assertThat(intent.id()).isEqualTo("pi_123");
        assertThat(intent.clientSecret()).isEqualTo("pi_123_secret_abc");
        stripe.verify(postRequestedFor(urlPathEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", equalTo("pay-key-1"))
                .withRequestBody(containing("amount=24950"))
                .withRequestBody(containing("currency=usd"))
                .withRequestBody(containing("metadata[bookingReference]=K7QX2M")));
    }

    @Test
    void mapsRefundStatuses() {
        stripe.stubFor(post(urlPathEqualTo("/v1/refunds")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"re_1\",\"object\":\"refund\",\"status\":\"pending\"}")));

        PaymentProvider.ProviderRefund refund = provider.refund("pi_123", Money.of("10.00", "USD"), "re-key");

        assertThat(refund.id()).isEqualTo("re_1");
        assertThat(refund.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(StripePaymentProvider.toStatus("succeeded")).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(StripePaymentProvider.toStatus("canceled")).isEqualTo(RefundStatus.FAILED);
        assertThat(StripePaymentProvider.toStatus(null)).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void serverErrorsAreTransientAndInvalidRequestsArePermanent() {
        stripe.stubFor(post(urlPathEqualTo("/v1/payment_intents")).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}")));
        assertThatThrownBy(() -> provider.createIntent(Money.of("1", "USD"), Map.of(), "k1"))
                .isInstanceOf(ProviderUnavailableException.class);

        stripe.stubFor(post(urlPathEqualTo("/v1/refunds")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Charge already refunded\"}}")));
        assertThatThrownBy(() -> provider.refund("pi_1", Money.of("1", "USD"), "k2"))
                .isInstanceOf(ProviderRejectedException.class)
                .hasMessageContaining("already refunded");
    }

    @Test
    void resilientWrapperRetriesTransientFailuresAndTranslatesErrors() {
        stripe.stubFor(post(urlPathEqualTo("/v1/payment_intents")).willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"type\":\"api_error\",\"message\":\"unavailable\"}}")));
        ResilientPaymentProvider resilient = new ResilientPaymentProvider(provider, Bulkhead.ofDefaults("b"),
                CircuitBreaker.ofDefaults("cb"), Retry.of("r", RetryConfig.custom().maxAttempts(3)
                .waitDuration(Duration.ofMillis(5)).retryExceptions(ProviderUnavailableException.class).build()));

        assertThatThrownBy(() -> resilient.createIntent(Money.of("1", "USD"), Map.of(), "k3"))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.SERVICE_UNAVAILABLE);
        stripe.verify(3, postRequestedFor(urlPathEqualTo("/v1/payment_intents")));

        stripe.stubFor(post(urlPathEqualTo("/v1/refunds")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"nope\"}}")));
        assertThatThrownBy(() -> resilient.refund("pi_1", Money.of("1", "USD"), "k4"))
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.PROVIDER_REJECTED);
        stripe.verify(1, postRequestedFor(urlPathEqualTo("/v1/refunds")));
    }

    @Test
    void fakeProviderHonoursIdempotencyKeys() {
        FakePaymentProvider fake = new FakePaymentProvider();

        assertThat(fake.createIntent(Money.of("1", "USD"), Map.of(), "same"))
                .isEqualTo(fake.createIntent(Money.of("1", "USD"), Map.of(), "same"));
        assertThat(fake.refund("pi", Money.of("1", "USD"), "r").status()).isEqualTo(RefundStatus.SUCCEEDED);
        fake.cancelIntent("pi", "c");
    }
}
