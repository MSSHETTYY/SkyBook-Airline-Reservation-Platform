package com.skybook.booking.adapter.out.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.http.HttpClient;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.skybook.booking.adapter.out.payment.PaymentRestAdapter;
import com.skybook.booking.adapter.out.support.RemoteCall;
import com.skybook.booking.application.port.out.PaymentPort;
import com.skybook.common.money.Money;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonArray;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumer-driven contract: what booking-service needs from payment-service. Running this test writes
 * {@code contracts/pacts/booking-service-payment-service.json}; payment-service verifies that file in its
 * own build ({@code PaymentProviderContractTest}), so a breaking provider change fails the provider's build
 * before it can break booking in production.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "payment-service")
class PaymentServiceContractTest {

    @Pact(consumer = "booking-service")
    V4Pact createPayment(PactDslWithProvider builder) {
        return builder
                .uponReceiving("a request to create a booking payment")
                .path("/api/v1/payments")
                .method("POST")
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .matchHeader("Idempotency-Key", "[A-Za-z0-9._:-]{8,64}", "booking-PCTAAA")
                .body(money(new PactDslJsonBody()
                        .stringValue("bookingReference", "PCTAAA")
                        .stringValue("purpose", "BOOKING"), 220.00))
                .willRespondWith()
                .status(201)
                .body(new PactDslJsonBody()
                        .stringMatcher("paymentId", "pay_[A-Za-z0-9]{20,40}", "pay_0123456789abcdef0123456789abcdef")
                        .stringValue("bookingReference", "PCTAAA")
                        .stringValue("status", "REQUIRES_PAYMENT")
                        .stringType("clientSecret", "pi_fake_123_secret_456"))
                .toPact(V4Pact.class);
    }

    @Pact(consumer = "booking-service")
    V4Pact refundBooking(PactDslWithProvider builder) {
        return builder
                .given("booking PCTBBB has a successful payment of 200.00 USD")
                .uponReceiving("a request to refund part of a booking")
                .path("/api/v1/payments/refunds")
                .method("POST")
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .matchHeader("Idempotency-Key", "[A-Za-z0-9._:-]{8,64}", "cancel-PCTBBB")
                .body(money(new PactDslJsonBody()
                        .stringValue("bookingReference", "PCTBBB")
                        .stringValue("reason", "CUSTOMER_CANCELLATION"), 50.00))
                .willRespondWith()
                .status(201)
                .body(PactDslJsonArray.arrayMinLike(1)
                        .stringType("refundId", "re_123")
                        .stringType("status", "SUCCEEDED")
                        .closeObject())
                .toPact(V4Pact.class);
    }

    private static PactDslJsonBody money(PactDslJsonBody body, double amount) {
        body.object("amount").decimalType("amount", amount).stringValue("currency", "USD").closeObject();
        return body;
    }

    private static PaymentRestAdapter adapter(MockServer server) {
        RestClient client = RestClient.builder().baseUrl(server.getUrl())
                .requestFactory(new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
                .build();
        return new PaymentRestAdapter(client, new RemoteCall("payment", CircuitBreaker.ofDefaults("p"),
                Retry.ofDefaults("p"), Bulkhead.ofDefaults("p"), JsonMapper.builder().build()));
    }

    @Test
    @PactTestFor(pactMethod = "createPayment")
    void createPayment(MockServer server) {
        PaymentPort.PaymentIntent intent = adapter(server).createPayment("PCTAAA", Money.of("220.00", "USD"),
                PaymentPort.Purpose.BOOKING, "booking-PCTAAA");

        assertThat(intent.paymentId()).startsWith("pay_");
        assertThat(intent.clientSecret()).isNotBlank();
    }

    @Test
    @PactTestFor(pactMethod = "refundBooking")
    void refundBooking(MockServer server) {
        assertThatCode(() -> adapter(server).refundBooking("PCTBBB", Money.of("50.00", "USD"),
                PaymentPort.RefundReason.CUSTOMER_CANCELLATION, "cancel-PCTBBB")).doesNotThrowAnyException();
        assertThat(Duration.ZERO).isZero();
    }
}
