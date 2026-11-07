package com.skybook.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.skybook.common.money.Money;

/** A (partial) refund of a payment. {@code PENDING -> SUCCEEDED | FAILED}, driven by the provider. */
@Entity
@Table(name = "refund")
public class Refund {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Column(name = "provider_refund_id", nullable = false)
    private String providerRefundId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false)
    private RefundStatus status;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false)
    private RefundReason reason;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Nullable on purpose: a null version tells Spring Data the entity is new (ids are assigned by us). */
    @Version
    private Long version;

    protected Refund() {
        // for JPA
    }

    /** Records a refund the provider accepted; a provider-confirmed refund is applied to the payment at once. */
    public static Refund record(Payment payment, Money amount, RefundReason reason, String providerRefundId,
                                RefundStatus providerStatus, String idempotencyKey, Instant now) {
        payment.requireRefundable(amount);
        Refund refund = new Refund();
        refund.id = "re_" + UUID.randomUUID().toString().replace("-", "");
        refund.payment = payment;
        refund.providerRefundId = providerRefundId;
        refund.status = RefundStatus.PENDING;
        refund.amount = amount.amount();
        refund.reason = reason;
        refund.idempotencyKey = idempotencyKey;
        refund.createdAt = now;
        refund.updatedAt = now;
        payment.addRefund(refund);
        if (providerStatus == RefundStatus.SUCCEEDED) {
            refund.markSucceeded(now);
        }
        return refund;
    }

    /** The provider confirmed the refund. Idempotent: returns {@code false} if already applied. */
    public boolean markSucceeded(Instant now) {
        if (status == RefundStatus.SUCCEEDED) {
            return false;
        }
        if (status == RefundStatus.FAILED) {
            throw new IllegalStateException("Refund " + id + " already failed");
        }
        status = RefundStatus.SUCCEEDED;
        updatedAt = now;
        payment.applySucceededRefund(amount(), now);
        return true;
    }

    public boolean markFailed(Instant now) {
        if (status != RefundStatus.PENDING) {
            return false;
        }
        status = RefundStatus.FAILED;
        updatedAt = now;
        return true;
    }

    public Money amount() {
        return Money.of(amount, payment.amount().currencyCode());
    }

    public String getId() {
        return id;
    }

    public Payment getPayment() {
        return payment;
    }

    public String getProviderRefundId() {
        return providerRefundId;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public RefundReason getReason() {
        return reason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
