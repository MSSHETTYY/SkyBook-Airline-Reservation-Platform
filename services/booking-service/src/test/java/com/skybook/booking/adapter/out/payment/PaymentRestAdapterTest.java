package com.skybook.booking.adapter.out.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.skybook.booking.adapter.out.support.RemoteCall;
import com.skybook.booking.application.port.out.PaymentPort;
import com.skybook.booking.domain.model.BookingErrorCode;
import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.money.Money;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import tools.jackson.databind.json.JsonMapper;

class PaymentRestAdapterTest {

    private static WireMockServer server;
    private PaymentRestAdapter adapter;

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        server.resetAll();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
        RestClient client = RestClient.builder().baseUrl(server.baseUrl()).requestFactory(factory).build();
        adapter = new PaymentRestAdapter(client, new RemoteCall("payment", CircuitBreaker.ofDefaults("p"),
                Retry.of("p", RetryConfig.custom().maxAttempts(2).waitDuration(Duration.ofMillis(5))
                        .retryExceptions(RemoteCall.Unavailable.class).build()),
                Bulkhead.ofDefaults("p"), JsonMapper.builder().build()));
    }

    @Test
    void createsPaymentsWithTheGeneratedContract() {
        server.stubFor(post(urlPathEqualTo("/api/v1/payments")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"paymentId":"pay_123","bookingReference":"K7QX2M","purpose":"BOOKING",
                         "status":"REQUIRES_PAYMENT","amount":{"amount":200.00,"currency":"USD"},
                         "amountRefunded":{"amount":0,"currency":"USD"},"clientSecret":"pi_1_secret_2",
                         "createdAt":"2026-12-01T08:00:00Z"}""")));

        PaymentPort.PaymentIntent intent = adapter.createPayment("K7QX2M", Money.of("200.00", "USD"),
                PaymentPort.Purpose.BOOKING, "booking-K7QX2M");

        assertThat(intent.paymentId()).isEqualTo("pay_123");
        assertThat(intent.clientSecret()).isEqualTo("pi_1_secret_2");
        server.verify(postRequestedFor(urlPathEqualTo("/api/v1/payments"))
                .withHeader("Idempotency-Key", equalTo("booking-K7QX2M"))
                .withRequestBody(equalToJson("""
                        {"bookingReference":"K7QX2M","amount":{"amount":200.00,"currency":"USD"},"purpose":"BOOKING"}
                        """)));
    }

    @Test
    void refundsAndCancellationsAndErrorMapping() {
        server.stubFor(post(urlPathEqualTo("/api/v1/payments/refunds")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("[]")));
        server.stubFor(post(urlPathEqualTo("/api/v1/payments/pay_1/cancellation")).willReturn(aResponse()
                .withStatus(409).withHeader("Content-Type", "application/problem+json")
                .withBody("{\"code\":\"REFUND_EXCEEDS_PAYMENT\",\"detail\":\"too much\"}")));
        server.stubFor(post(urlPathEqualTo("/api/v1/payments/pay_2/cancellation"))
                .willReturn(aResponse().withStatus(503)));

        adapter.refundBooking("K7QX2M", Money.of("50.00", "USD"), PaymentPort.RefundReason.MANUAL, "manual-1");
        server.verify(postRequestedFor(urlPathEqualTo("/api/v1/payments/refunds"))
                .withHeader("Idempotency-Key", equalTo("manual-1"))
                .withRequestBody(equalToJson("""
                        {"bookingReference":"K7QX2M","amount":{"amount":50.00,"currency":"USD"},"reason":"MANUAL"}
                        """)));
        assertThatThrownBy(() -> adapter.cancelPayment("pay_1"))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.REFUND_EXCEEDS_PAYMENT);
        assertThatThrownBy(() -> adapter.cancelPayment("pay_2"))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.SERVICE_UNAVAILABLE);
        server.verify(2, postRequestedFor(urlPathEqualTo("/api/v1/payments/pay_2/cancellation")));
        assertThat(RemoteCall.toErrorCode(null, 418)).isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
    }
}
