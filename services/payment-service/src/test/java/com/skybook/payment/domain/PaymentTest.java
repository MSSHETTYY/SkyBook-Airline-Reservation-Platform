package com.skybook.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.skybook.common.money.Money;

class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-12-01T08:00:00Z");

    private static Payment payment() {
        return Payment.create("K7QX2M", PaymentPurpose.BOOKING, Money.of("200.00", "USD"), "pi_1", "secret",
                "key-1", "fp", "corr-1", NOW);
    }

    private static Payment succeeded() {
        Payment payment = payment();
        payment.markSucceeded(Money.of("200.00", "USD"), NOW);
        return payment;
    }

    @Test
    void newPaymentAwaitsTheCustomer() {
        Payment payment = payment();

        assertThat(payment.getId()).matches("pay_[0-9a-f]{32}");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT);
        assertThat(payment.getClientSecret()).isEqualTo("secret");
        assertThat(payment.amountRefunded().isZero()).isTrue();
        assertThat(payment.getCorrelationId()).isEqualTo("corr-1");
        assertThat(payment.matchesRequest("fp")).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> Payment.create("K7QX2M", PaymentPurpose.BOOKING,
                Money.of("0", "USD"), "pi", null, "k", "f", null, NOW));
    }

    @Test
    void successIsIdempotentAndChecksTheCapturedAmount() {
        Payment payment = payment();

        assertThatIllegalStateException().isThrownBy(() -> payment.markSucceeded(Money.of("199.99", "USD"), NOW));
        assertThat(payment.markSucceeded(Money.of("200.00", "USD"), NOW)).isTrue();
        assertThat(payment.markSucceeded(Money.of("200.00", "USD"), NOW)).isFalse();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getClientSecret()).as("secret discarded once paid").isNull();
    }

    @Test
    void failedAttemptsKeepThePaymentPayable() {
        Payment payment = payment();

        assertThat(payment.recordFailedAttempt("Card declined", NOW)).isTrue();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT);
        assertThat(payment.getFailureReason()).isEqualTo("Card declined");
        assertThat(payment.recordFailedAttempt(null, NOW)).isTrue();
        assertThat(payment.getFailureReason()).isEqualTo("Payment failed");
        assertThat(succeeded().recordFailedAttempt("late", NOW)).isFalse();
    }

    @Test
    void onlyUnpaidPaymentsCanBeCancelled() {
        Payment payment = payment();

        assertThat(payment.cancel(NOW)).isTrue();
        assertThat(payment.cancel(NOW)).isFalse();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThatThrownBy(() -> succeeded().cancel(NOW))
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.PAYMENT_NOT_CANCELLABLE);
    }

    @Test
    void refundsAreBoundedByTheCapturedAmountIncludingPendingRefunds() {
        Payment payment = succeeded();

        Refund pending = Refund.record(payment, Money.of("150.00", "USD"), RefundReason.CUSTOMER_CANCELLATION,
                "re_1", RefundStatus.PENDING, "rk-1", NOW);
        assertThat(payment.amountRefunded().isZero()).as("not applied until confirmed").isTrue();
        assertThatThrownBy(() -> payment.requireRefundable(Money.of("50.01", "USD")))
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.REFUND_EXCEEDS_PAYMENT);

        assertThat(pending.markSucceeded(NOW)).isTrue();
        assertThat(pending.markSucceeded(NOW)).isFalse();
        assertThat(payment.amountRefunded()).isEqualTo(Money.of("150.00", "USD"));

        Refund rest = Refund.record(payment, Money.of("50.00", "USD"), RefundReason.MANUAL, "re_2",
                RefundStatus.SUCCEEDED, "rk-2", NOW);
        assertThat(rest.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(payment.amountRefunded()).isEqualTo(Money.of("200.00", "USD"));
        assertThat(payment.getRefunds()).hasSize(2);
    }

    @Test
    void failedRefundsFreeTheirAmountAgain() {
        Payment payment = succeeded();
        Refund refund = Refund.record(payment, Money.of("200.00", "USD"), RefundReason.MANUAL, "re_1",
                RefundStatus.PENDING, "rk-1", NOW);

        assertThat(refund.markFailed(NOW)).isTrue();
        assertThat(refund.markFailed(NOW)).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> refund.markSucceeded(NOW));
        payment.requireRefundable(Money.of("200.00", "USD"));
    }

    @Test
    void unpaidOrZeroRefundsAreRejected() {
        assertThatThrownBy(() -> payment().requireRefundable(Money.of("1", "USD")))
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.PAYMENT_NOT_REFUNDABLE);
        assertThatThrownBy(() -> succeeded().requireRefundable(Money.of("0", "USD")))
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.REFUND_EXCEEDS_PAYMENT);
    }
}
