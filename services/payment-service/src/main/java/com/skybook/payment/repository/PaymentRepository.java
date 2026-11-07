package com.skybook.payment.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.skybook.payment.domain.Payment;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByProviderPaymentId(String providerPaymentId);

    /** Served by ix_payment_booking. */
    List<Payment> findByBookingReferenceOrderByCreatedAtDesc(String bookingReference);
}
