package com.skybook.payment.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.skybook.payment.api.v1.model.Money;
import com.skybook.payment.api.v1.model.Payment;
import com.skybook.payment.api.v1.model.PaymentPurpose;
import com.skybook.payment.api.v1.model.PaymentStatus;
import com.skybook.payment.api.v1.model.Refund;
import com.skybook.payment.api.v1.model.RefundStatus;

/** Maps entities to the generated API models. */
final class PaymentApiMapper {

    private PaymentApiMapper() {
    }

    static Payment toApi(com.skybook.payment.domain.Payment payment) {
        return new Payment()
                .paymentId(payment.getId())
                .bookingReference(payment.getBookingReference())
                .purpose(PaymentPurpose.fromValue(payment.getPurpose().name()))
                .status(PaymentStatus.fromValue(payment.getStatus().name()))
                .amount(toApi(payment.amount()))
                .amountRefunded(toApi(payment.amountRefunded()))
                .clientSecret(payment.getClientSecret())
                .failureReason(payment.getFailureReason())
                .refunds(payment.getRefunds().stream().map(PaymentApiMapper::toApi).toList())
                .createdAt(toOffset(payment.getCreatedAt()));
    }

    static Refund toApi(com.skybook.payment.domain.Refund refund) {
        return new Refund()
                .refundId(refund.getId())
                .paymentId(refund.getPayment().getId())
                .status(RefundStatus.fromValue(refund.getStatus().name()))
                .amount(toApi(refund.amount()))
                .reason(refund.getReason().name())
                .createdAt(toOffset(refund.getCreatedAt()));
    }

    static Money toApi(com.skybook.common.money.Money money) {
        return new Money().amount(money.amount()).currency(money.currencyCode());
    }

    static com.skybook.common.money.Money toDomain(Money money) {
        return com.skybook.common.money.Money.of(money.getAmount(), money.getCurrency());
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
