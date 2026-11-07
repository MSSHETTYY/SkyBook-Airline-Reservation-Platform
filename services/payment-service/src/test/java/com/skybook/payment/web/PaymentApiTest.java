package com.skybook.payment.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.skybook.common.security.Roles;
import com.skybook.payment.provider.StripeSignatures;
import com.skybook.payment.support.PaymentTestSupport;

import io.restassured.http.ContentType;

class PaymentApiTest extends PaymentTestSupport {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String reference() {
        int n = SEQUENCE.incrementAndGet();
        return "PAY" + "ABCDEFGHJK".charAt(n % 10) + "ABCDEFGHJK".charAt((n / 10) % 10)
                + "ABCDEFGHJK".charAt((n / 100) % 10);
    }

    private static String paymentJson(String bookingReference, String amount) {
        return """
                {"bookingReference":"%s","amount":{"amount":%s,"currency":"USD"},"purpose":"BOOKING"}"""
                .formatted(bookingReference, amount);
    }

    private String createPayment(String bookingReference, String amount, String key) {
        return given().header("Authorization", SERVICE).header("Idempotency-Key", key)
                .contentType(ContentType.JSON)
                .body(paymentJson(bookingReference, amount))
                .when().post("/api/v1/payments")
                .then().statusCode(201)
                .body("status", equalTo("REQUIRES_PAYMENT"))
                .body("clientSecret", startsWith("pi_fake_"))
                .extract().path("paymentId");
    }

    private void simulate(String paymentId, String outcome) {
        given().header("Authorization", bearer("cust-1", Roles.CUSTOMER)).contentType(ContentType.JSON)
                .body("{\"outcome\":\"" + outcome + "\"}")
                .when().post("/api/v1/payments/{id}/simulation", paymentId)
                .then().statusCode(202);
    }

    @Test
    void createdPaymentIsIdempotentPerKey() {
        String ref = reference();
        String first = createPayment(ref, "210.00", "pay-key-0001");
        String replay = createPayment(ref, "210.00", "pay-key-0001");

        assertThat(replay).isEqualTo(first);
        given().header("Authorization", SERVICE).header("Idempotency-Key", "pay-key-0001")
                .contentType(ContentType.JSON)
                .body(paymentJson(ref, "999"))
                .when().post("/api/v1/payments")
                .then().statusCode(422).body("code", equalTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void onlyServicesCanCreatePayments() {
        given().header("Authorization", bearer("cust-1", Roles.CUSTOMER))
                .header("Idempotency-Key", "pay-key-0002")
                .contentType(ContentType.JSON)
                .body(paymentJson("K7QX2M", "1"))
                .when().post("/api/v1/payments")
                .then().statusCode(403);
    }

    @Test
    void successfulPaymentPublishesPaymentCompletedOnce() {
        String ref = reference();
        String paymentId = createPayment(ref, "210.00", "pay-key-0003");

        simulate(paymentId, "SUCCEEDED");

        given().header("Authorization", SERVICE).when().get("/api/v1/payments/{id}", paymentId)
                .then().statusCode(200)
                .body("status", equalTo("SUCCEEDED"))
                .body("clientSecret", nullValue());
        assertThat(outboxEvents("PaymentCompleted", ref)).isEqualTo(1);
    }

    @Test
    void failedAttemptPublishesPaymentFailedAndKeepsThePaymentPayable() {
        String ref = reference();
        String paymentId = createPayment(ref, "99.00", "pay-key-0004");

        simulate(paymentId, "FAILED");

        given().header("Authorization", SERVICE).when().get("/api/v1/payments/{id}", paymentId)
                .then().body("status", equalTo("REQUIRES_PAYMENT"))
                .body("failureReason", equalTo("Your card was declined (simulated)."));
        assertThat(outboxEvents("PaymentFailed", ref)).isEqualTo(1);
    }

    @Test
    void webhooksRequireAValidSignatureAndAreDeduplicated() {
        String ref = reference();
        String paymentId = createPayment(ref, "50.00", "pay-key-0005");
        String providerId = jdbc.queryForObject("select provider_payment_id from payment where id = ?", String.class,
                paymentId);
        String event = """
                {"id":"evt_dup_1_%s","type":"payment_intent.succeeded","data":{"object":{"id":"%s",\
                "amount_received":5000,"currency":"usd"}}}""".formatted(ref, providerId);

        given().header("Stripe-Signature", StripeSignatures.sign(event, "whsec_wrong", Instant.now()))
                .contentType(ContentType.JSON).body(event)
                .when().post("/api/v1/payments/webhooks/stripe")
                .then().statusCode(400).body("code", equalTo("INVALID_WEBHOOK_SIGNATURE"));
        assertThat(outboxEvents("PaymentCompleted", ref)).isZero();

        for (int i = 0; i < 2; i++) {
            given().header("Stripe-Signature", nowSignatureTimestampFor(event))
                    .contentType(ContentType.JSON).body(event)
                    .when().post("/api/v1/payments/webhooks/stripe")
                    .then().statusCode(200);
        }
        assertThat(outboxEvents("PaymentCompleted", ref)).as("redelivery is a no-op").isEqualTo(1);

        String stale = StripeSignatures.sign(event, WEBHOOK_SECRET, Instant.now().minusSeconds(3600));
        given().header("Stripe-Signature", stale).contentType(ContentType.JSON).body(event)
                .when().post("/api/v1/payments/webhooks/stripe")
                .then().statusCode(400);
    }

    @Test
    void refundsArePartialBoundedAndPublished() {
        String ref = reference();
        String paymentId = createPayment(ref, "200.00", "pay-key-0006");
        simulate(paymentId, "SUCCEEDED");

        given().header("Authorization", SERVICE).header("Idempotency-Key", "refund-key-0001")
                .contentType(ContentType.JSON)
                .body("{\"amount\":{\"amount\":150,\"currency\":\"USD\"},\"reason\":\"CUSTOMER_CANCELLATION\"}")
                .when().post("/api/v1/payments/{id}/refunds", paymentId)
                .then().statusCode(201).body("status", equalTo("SUCCEEDED"));
        given().header("Authorization", SERVICE).header("Idempotency-Key", "refund-key-0001")
                .contentType(ContentType.JSON)
                .body("{\"amount\":{\"amount\":150,\"currency\":\"USD\"},\"reason\":\"CUSTOMER_CANCELLATION\"}")
                .when().post("/api/v1/payments/{id}/refunds", paymentId)
                .then().statusCode(201);
        given().header("Authorization", SERVICE).header("Idempotency-Key", "refund-key-0002")
                .contentType(ContentType.JSON)
                .body("{\"amount\":{\"amount\":60,\"currency\":\"USD\"},\"reason\":\"MANUAL\"}")
                .when().post("/api/v1/payments/{id}/refunds", paymentId)
                .then().statusCode(422).body("code", equalTo("REFUND_EXCEEDS_PAYMENT"));

        assertThat(outboxEvents("RefundIssued", ref)).isEqualTo(1);
        given().header("Authorization", bearer("agent-1", Roles.AGENT)).queryParam("bookingReference", ref)
                .when().get("/api/v1/admin/payments")
                .then().statusCode(200)
                .body("[0].amountRefunded.amount", equalTo(150.0f))
                .body("[0].refunds", hasSize(1));
    }

    @Test
    void bookingRefundsSpreadOverPaymentsNewestFirst() {
        String ref = reference();
        String original = createPayment(ref, "200.00", "pay-key-0009");
        simulate(original, "SUCCEEDED");
        String change = createPayment(ref, "80.00", "pay-key-0010");
        simulate(change, "SUCCEEDED");
        String body = bookingRefundJson(ref, "100");

        given().header("Authorization", SERVICE).header("Idempotency-Key", "booking-refund-0001")
                .contentType(ContentType.JSON).body(body)
                .when().post("/api/v1/payments/refunds")
                .then().statusCode(201)
                .body("", hasSize(2))
                .body("[0].paymentId", equalTo(change))
                .body("[0].amount.amount", equalTo(80.0f))
                .body("[1].paymentId", equalTo(original))
                .body("[1].amount.amount", equalTo(20.0f));
        given().header("Authorization", SERVICE).header("Idempotency-Key", "booking-refund-0001")
                .contentType(ContentType.JSON).body(body)
                .when().post("/api/v1/payments/refunds")
                .then().statusCode(201).body("", hasSize(2));
        assertThat(outboxEvents("RefundIssued", ref)).as("retry re-uses the refunds").isEqualTo(2);

        given().header("Authorization", SERVICE).header("Idempotency-Key", "booking-refund-0002")
                .contentType(ContentType.JSON).body(bookingRefundJson(ref, "181"))
                .when().post("/api/v1/payments/refunds")
                .then().statusCode(422).body("code", equalTo("REFUND_EXCEEDS_PAYMENT"));
    }

    private static String bookingRefundJson(String bookingReference, String amount) {
        return """
                {"bookingReference":"%s","amount":{"amount":%s,"currency":"USD"},"reason":"CUSTOMER_CANCELLATION"}"""
                .formatted(bookingReference, amount);
    }

    @Test
    void unpaidPaymentsCanBeCancelledButPaidOnesCannot() {
        String unpaid = createPayment(reference(), "10.00", "pay-key-0007");
        given().header("Authorization", SERVICE).when().post("/api/v1/payments/{id}/cancellation", unpaid)
                .then().statusCode(200).body("status", equalTo("CANCELLED"));

        String paid = createPayment(reference(), "10.00", "pay-key-0008");
        simulate(paid, "SUCCEEDED");
        given().header("Authorization", SERVICE).when().post("/api/v1/payments/{id}/cancellation", paid)
                .then().statusCode(409).body("code", equalTo("PAYMENT_NOT_CANCELLABLE"));
        given().header("Authorization", SERVICE).header("Idempotency-Key", "refund-key-0003")
                .contentType(ContentType.JSON)
                .body("{\"amount\":{\"amount\":1,\"currency\":\"USD\"},\"reason\":\"MANUAL\"}")
                .when().post("/api/v1/payments/{id}/refunds", unpaid)
                .then().statusCode(409).body("code", equalTo("PAYMENT_NOT_REFUNDABLE"));
    }
}
