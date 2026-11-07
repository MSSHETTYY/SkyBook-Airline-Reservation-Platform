package com.skybook.payment.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import com.skybook.common.security.Roles;
import com.skybook.payment.api.v1.AdminPaymentsApi;
import com.skybook.payment.api.v1.model.Payment;
import com.skybook.payment.service.PaymentService;

@RestController
class AdminPaymentsController implements AdminPaymentsApi {

    private final PaymentService payments;

    AdminPaymentsController(PaymentService payments) {
        this.payments = payments;
    }

    @Override
    @PreAuthorize(Roles.IS_STAFF)
    public ResponseEntity<List<Payment>> listPaymentsForBooking(String bookingReference) {
        return ResponseEntity.ok(payments.listForBooking(bookingReference).stream()
                .map(PaymentApiMapper::toApi).toList());
    }
}
