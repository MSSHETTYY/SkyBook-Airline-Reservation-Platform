package com.skybook.booking.adapter.out.payment;

import org.springframework.web.client.RestClient;

import com.skybook.booking.adapter.out.payment.model.BookingRefundRequest;
import com.skybook.booking.adapter.out.payment.model.CreatePaymentRequest;
import com.skybook.booking.adapter.out.payment.model.Money;
import com.skybook.booking.adapter.out.payment.model.Payment;
import com.skybook.booking.adapter.out.payment.model.PaymentPurpose;
import com.skybook.booking.adapter.out.support.RemoteCall;
import com.skybook.booking.application.port.out.PaymentPort;
import com.skybook.common.web.CorrelationId;

/** {@link PaymentPort} over payment-service's REST API, using DTOs generated from its OpenAPI spec. */
public class PaymentRestAdapter implements PaymentPort {

    private final RestClient restClient;
    private final RemoteCall remote;

    public PaymentRestAdapter(RestClient restClient, RemoteCall remote) {
        this.restClient = restClient;
        this.remote = remote;
    }

    @Override
    public PaymentIntent createPayment(String bookingReference, com.skybook.common.money.Money amount,
                                       Purpose purpose, String idempotencyKey) {
        Payment payment = remote.execute("createPayment", () -> restClient.post()
                .uri("/api/v1/payments")
                .header("Idempotency-Key", idempotencyKey)
                .header(CorrelationId.HEADER, CorrelationId.current().orElse(null))
                .body(new CreatePaymentRequest()
                        .bookingReference(bookingReference)
                        .amount(toApi(amount))
                        .purpose(PaymentPurpose.fromValue(purpose.name())))
                .retrieve()
                .onStatus(status -> status.isError(), remote::raise)
                .body(Payment.class));
        return new PaymentIntent(payment.getPaymentId(), payment.getClientSecret());
    }

    @Override
    public void cancelPayment(String paymentId) {
        remote.execute("cancelPayment", () -> restClient.post()
                .uri("/api/v1/payments/{paymentId}/cancellation", paymentId)
                .header(CorrelationId.HEADER, CorrelationId.current().orElse(null))
                .retrieve()
                .onStatus(status -> status.isError(), remote::raise)
                .toBodilessEntity());
    }

    @Override
    public void refundBooking(String bookingReference, com.skybook.common.money.Money amount, RefundReason reason,
                              String idempotencyKey) {
        remote.execute("refundBooking", () -> restClient.post()
                .uri("/api/v1/payments/refunds")
                .header("Idempotency-Key", idempotencyKey)
                .header(CorrelationId.HEADER, CorrelationId.current().orElse(null))
                .body(new BookingRefundRequest()
                        .bookingReference(bookingReference)
                        .amount(toApi(amount))
                        .reason(BookingRefundRequest.ReasonEnum.fromValue(reason.name())))
                .retrieve()
                .onStatus(status -> status.isError(), remote::raise)
                .toBodilessEntity());
    }

    private static Money toApi(com.skybook.common.money.Money money) {
        return new Money().amount(money.amount()).currency(money.currencyCode());
    }
}
