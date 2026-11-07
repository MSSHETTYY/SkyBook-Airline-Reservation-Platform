package com.skybook.payment.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.skybook.payment.domain.Refund;

public interface RefundRepository extends JpaRepository<Refund, String> {

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    Optional<Refund> findByProviderRefundId(String providerRefundId);
}
