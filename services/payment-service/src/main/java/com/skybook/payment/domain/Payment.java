package com.skybook.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.skybook.common.crypto.EncryptedStringConverter;
import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;

/**
 * A payment for a booking or a date change, backed by one Stripe PaymentIntent.
 *
 * <p>State machine: {@code REQUIRES_PAYMENT -> SUCCEEDED | CANCELLED}. A failed card attempt does not
 * change the state (the customer can retry with the same intent); it is recorded and published as
 * PaymentFailed. Refunds never exceed the captured amount.
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    private String id;

    @Column(name = "booking_reference", nullable = false)
    private String bookingReference;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false)
    private PaymentPurpose purpose;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "amount_refunded", nullable = false)
    private BigDecimal amountRefunded;

    @Column(name = "provider_payment_id", nullable = false)
    private String providerPaymentId;

    /** The Stripe client secret lets anyone holding it complete the payment, so it is encrypted at rest. */
    @Column(name = "client_secret")
    @Convert(converter = EncryptedStringConverter.class)
    private String clientSecret;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, updatable = false)
    private String requestFingerprint;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Nullable on purpose: a null version tells Spring Data the entity is new (ids are assigned by us). */
    @Version
    private Long version;

    /** Few refunds per payment and always shown with it, so loaded eagerly. */
    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @OrderBy("createdAt")
    private List<Refund> refunds = new ArrayList<>();

    protected Payment() {
        // for JPA
    }

    public static Payment create(String bookingReference, PaymentPurpose purpose, Money amount,
                                 String providerPaymentId, String clientSecret, String idempotencyKey,
                                 String requestFingerprint, String correlationId, Instant now) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
        Payment payment = new Payment();
        payment.id = newId();
        payment.bookingReference = bookingReference;
        payment.purpose = purpose;
        payment.status = PaymentStatus.REQUIRES_PAYMENT;
        payment.amount = amount.amount();
        payment.currency = amount.currencyCode();
        payment.amountRefunded = BigDecimal.ZERO.setScale(amount.amount().scale());
        payment.providerPaymentId = providerPaymentId;
        payment.clientSecret = clientSecret;
        payment.idempotencyKey = idempotencyKey;
        payment.requestFingerprint = requestFingerprint;
        payment.correlationId = correlationId;
        payment.createdAt = now;
        payment.updatedAt = now;
        return payment;
    }

    /** Public payment id, e.g. {@code pay_3f2b8c1e8d1a4f7e9a510c2d}; never the provider's id. */
    static String newId() {
        return "pay_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * The provider confirmed the payment. Idempotent: returns {@code false} if it was already recorded.
     *
     * @throws SkyBookException if the captured amount differs from the requested one (never trust silently)
     */
    public boolean markSucceeded(Money captured, Instant now) {
        if (status == PaymentStatus.SUCCEEDED) {
            return false;
        }
        if (!captured.equals(amount())) {
            throw new IllegalStateException("Captured %s but expected %s for payment %s".formatted(captured,
                    amount(), id));
        }
        status = PaymentStatus.SUCCEEDED;
        clientSecret = null;
        failureReason = null;
        updatedAt = now;
        return true;
    }

    /** A card attempt failed; the payment stays payable. Returns {@code false} if it already succeeded. */
    public boolean recordFailedAttempt(String reason, Instant now) {
        if (status != PaymentStatus.REQUIRES_PAYMENT) {
            return false;
        }
        failureReason = reason == null ? "Payment failed" : reason.substring(0, Math.min(reason.length(), 255));
        updatedAt = now;
        return true;
    }

    /** Abandons an unpaid payment; idempotent. */
    public boolean cancel(Instant now) {
        if (status == PaymentStatus.CANCELLED) {
            return false;
        }
        if (status == PaymentStatus.SUCCEEDED) {
            throw new SkyBookException(PaymentErrorCode.PAYMENT_NOT_CANCELLABLE,
                    "Payment %s already succeeded; refund it instead".formatted(id));
        }
        status = PaymentStatus.CANCELLED;
        clientSecret = null;
        updatedAt = now;
        return true;
    }

    /** Validates a refund request against what was captured and already refunded or pending. */
    public void requireRefundable(Money requested) {
        if (status != PaymentStatus.SUCCEEDED) {
            throw new SkyBookException(PaymentErrorCode.PAYMENT_NOT_REFUNDABLE,
                    "Payment %s is %s".formatted(id, status));
        }
        Money committed = refunds.stream()
                .filter(refund -> refund.getStatus() != RefundStatus.FAILED)
                .map(Refund::amount)
                .reduce(Money.zero(amount().currency()), Money::plus);
        if (!requested.isPositive() || committed.plus(requested).isGreaterThan(amount())) {
            throw new SkyBookException(PaymentErrorCode.REFUND_EXCEEDS_PAYMENT,
                    "Refund of %s exceeds the refundable %s".formatted(requested, amount().minus(committed)));
        }
    }

    void addRefund(Refund refund) {
        refunds.add(refund);
    }

    void applySucceededRefund(Money refunded, Instant now) {
        amountRefunded = amountRefunded.add(refunded.amount());
        updatedAt = now;
    }

    public Money amount() {
        return Money.of(amount, currency);
    }

    public Money amountRefunded() {
        return Money.of(amountRefunded, currency);
    }

    public String getId() {
        return id;
    }

    public String getBookingReference() {
        return bookingReference;
    }

    public PaymentPurpose getPurpose() {
        return purpose;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public boolean matchesRequest(String fingerprint) {
        return requestFingerprint.equals(fingerprint);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public List<Refund> getRefunds() {
        return List.copyOf(refunds);
    }
}
