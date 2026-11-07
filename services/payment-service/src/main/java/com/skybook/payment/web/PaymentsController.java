package com.skybook.payment.web;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import com.skybook.common.security.Roles;
import com.skybook.payment.api.v1.PaymentsApi;
import com.skybook.payment.api.v1.model.BookingRefundRequest;
import com.skybook.payment.api.v1.model.CreatePaymentRequest;
import com.skybook.payment.api.v1.model.Payment;
import com.skybook.payment.api.v1.model.Refund;
import com.skybook.payment.api.v1.model.RefundRequest;
import com.skybook.payment.domain.PaymentPurpose;
import com.skybook.payment.domain.RefundReason;
import com.skybook.payment.service.PaymentService;

/** Payment commands are an internal API for booking-service (role SERVICE); staff may read payments. */
@RestController
class PaymentsController implements PaymentsApi {

    private final PaymentService payments;

    PaymentsController(PaymentService payments) {
        this.payments = payments;
    }

    @Override
    @PreAuthorize(Roles.IS_SERVICE)
    public ResponseEntity<Payment> createPayment(String idempotencyKey, CreatePaymentRequest request) {
        var payment = payments.create(request.getBookingReference(),
                PaymentPurpose.valueOf(request.getPurpose().getValue()),
                PaymentApiMapper.toDomain(request.getAmount()), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/payments/" + payment.getId()))
                .body(PaymentApiMapper.toApi(payment));
    }

    @Override
    @PreAuthorize("hasAnyRole('SERVICE', 'AGENT', 'ADMIN')")
    public ResponseEntity<Payment> getPayment(String paymentId) {
        return ResponseEntity.ok(PaymentApiMapper.toApi(payments.get(paymentId)));
    }

    @Override
    @PreAuthorize(Roles.IS_SERVICE)
    public ResponseEntity<Payment> cancelPayment(String paymentId) {
        return ResponseEntity.ok(PaymentApiMapper.toApi(payments.cancel(paymentId)));
    }

    @Override
    @PreAuthorize(Roles.IS_SERVICE)
    public ResponseEntity<List<Refund>> refundBooking(String idempotencyKey, BookingRefundRequest request) {
        var refunds = payments.refundBooking(request.getBookingReference(),
                PaymentApiMapper.toDomain(request.getAmount()), RefundReason.valueOf(request.getReason().getValue()),
                idempotencyKey);
        return ResponseEntity.status(201).body(refunds.stream().map(PaymentApiMapper::toApi).toList());
    }

    @Override
    @PreAuthorize(Roles.IS_SERVICE)
    public ResponseEntity<Refund> refundPayment(String paymentId, String idempotencyKey, RefundRequest request) {
        var refund = payments.refund(paymentId, PaymentApiMapper.toDomain(request.getAmount()),
                RefundReason.valueOf(request.getReason().getValue()), idempotencyKey);
        return ResponseEntity.status(201).body(PaymentApiMapper.toApi(refund));
    }
}
