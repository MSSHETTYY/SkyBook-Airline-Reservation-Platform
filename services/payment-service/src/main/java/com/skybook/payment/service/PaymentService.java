package com.skybook.payment.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.error.ResourceNotFoundException;
import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;
import com.skybook.common.web.CorrelationId;
import com.skybook.payment.domain.Payment;
import com.skybook.payment.domain.PaymentErrorCode;
import com.skybook.payment.domain.PaymentPurpose;
import com.skybook.payment.domain.PaymentStatus;
import com.skybook.payment.domain.Refund;
import com.skybook.payment.domain.RefundReason;
import com.skybook.payment.domain.RefundStatus;
import com.skybook.payment.provider.PaymentProvider;
import com.skybook.payment.repository.PaymentRepository;
import com.skybook.payment.repository.RefundRepository;

/**
 * Payment and refund use cases.
 *
 * <p>Provider calls happen <em>outside</em> database transactions (a slow provider must not hold a
 * connection), and every call carries an idempotency key derived from the client's key. If the process
 * dies after the provider call but before our commit, the client's retry repeats the provider call with
 * the same key, gets the same intent or refund back, and commits it.
 */
@Service
public class PaymentService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentProvider provider;
    private final PaymentEvents events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, RefundRepository refunds, PaymentProvider provider,
                          PaymentEvents events, TransactionTemplate tx, Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.provider = provider;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    public Payment create(String bookingReference, PaymentPurpose purpose, Money amount, String idempotencyKey) {
        String fingerprint = bookingReference + "|" + purpose + "|" + amount;
        var existing = payments.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        PaymentProvider.ProviderIntent intent = provider.createIntent(amount,
                Map.of("bookingReference", bookingReference, "purpose", purpose.name()), "pay-" + idempotencyKey);
        try {
            Payment saved = tx.execute(status -> payments.saveAndFlush(Payment.create(bookingReference, purpose,
                    amount, intent.id(), intent.clientSecret(), idempotencyKey, fingerprint,
                    CorrelationId.current().orElse(null), clock.instant())));
            LOG.info("Payment {} created for booking {} ({}, {})", saved.getId(), bookingReference, purpose, amount);
            return saved;
        } catch (DataIntegrityViolationException ex) {
            return payments.findByIdempotencyKey(idempotencyKey).map(winner -> replay(winner, fingerprint))
                    .orElseThrow(() -> ex);
        }
    }

    public Payment get(String paymentId) {
        return tx.execute(status -> {
            Payment payment = find(paymentId);
            payment.getRefunds().size();
            return payment;
        });
    }

    public List<Payment> listForBooking(String bookingReference) {
        return tx.execute(status -> {
            List<Payment> found = payments.findByBookingReferenceOrderByCreatedAtDesc(bookingReference);
            found.forEach(payment -> payment.getRefunds().size());
            return found;
        });
    }

    /** Abandons an unpaid payment (the booking expired or was abandoned). Idempotent. */
    public Payment cancel(String paymentId) {
        Payment payment = get(paymentId);
        if (payment.getStatus() == PaymentStatus.REQUIRES_PAYMENT) {
            provider.cancelIntent(payment.getProviderPaymentId(), "cancel-" + paymentId);
        }
        return tx.execute(status -> {
            Payment current = find(paymentId);
            if (current.cancel(clock.instant())) {
                LOG.info("Payment {} of booking {} cancelled", paymentId, current.getBookingReference());
            }
            return current;
        });
    }

    /** Refunds part or all of a payment. Idempotent per key; RefundIssued is published once it succeeded. */
    public Refund refund(String paymentId, Money amount, RefundReason reason, String idempotencyKey) {
        var existing = refunds.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        Payment payment = get(paymentId);
        payment.requireRefundable(amount);
        PaymentProvider.ProviderRefund providerRefund = provider.refund(payment.getProviderPaymentId(), amount,
                "re-" + idempotencyKey);
        try {
            return tx.execute(status -> {
                Payment current = find(paymentId);
                Refund refund = refunds.save(Refund.record(current, amount, reason, providerRefund.id(),
                        providerRefund.status(), idempotencyKey, clock.instant()));
                if (refund.getStatus() == RefundStatus.SUCCEEDED) {
                    events.refundIssued(refund);
                }
                refunds.flush();
                LOG.info("Refund {} of {} for payment {} ({}): {}", refund.getId(), amount, paymentId, reason,
                        refund.getStatus());
                return refund;
            });
        } catch (DataIntegrityViolationException ex) {
            return refunds.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> ex);
        }
    }

    /**
     * Refunds {@code amount} for a booking from its successful payments, newest first, so a date-change
     * difference is returned before the original fare. Idempotent: each per-payment refund is keyed by
     * {@code <key>:<paymentId>}, so a retry re-uses the refunds already made.
     */
    public List<Refund> refundBooking(String bookingReference, Money amount, RefundReason reason,
                                      String idempotencyKey) {
        List<Payment> candidates = listForBooking(bookingReference).stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.SUCCEEDED)
                .toList();
        Money refundable = candidates.stream().map(PaymentService::refundableOf)
                .reduce(Money.zero(amount.currency()), Money::plus);
        boolean replay = candidates.stream().flatMap(payment -> payment.getRefunds().stream())
                .anyMatch(refund -> refund.getIdempotencyKey().startsWith(idempotencyKey + ":"));
        if (!replay && (!amount.isPositive() || amount.isGreaterThan(refundable))) {
            throw new SkyBookException(PaymentErrorCode.REFUND_EXCEEDS_PAYMENT,
                    "Refund of %s exceeds the refundable %s for booking %s".formatted(amount, refundable,
                            bookingReference));
        }
        List<Refund> created = new ArrayList<>();
        Money remaining = amount;
        for (Payment payment : candidates) {
            if (!remaining.isPositive()) {
                break;
            }
            String key = idempotencyKey + ":" + payment.getId();
            var previous = payment.getRefunds().stream()
                    .filter(refund -> refund.getIdempotencyKey().equals(key)).findFirst();
            Money available = refundableOf(payment);
            Money stillNeeded = remaining;
            Money portion = previous.map(Refund::amount)
                    .orElseGet(() -> available.isGreaterThan(stillNeeded) ? stillNeeded : available);
            if (portion.isPositive()) {
                created.add(previous.orElseGet(() -> refund(payment.getId(), portion, reason, key)));
                remaining = remaining.minus(portion);
            }
        }
        return created;
    }

    private static Money refundableOf(Payment payment) {
        Money committed = payment.getRefunds().stream()
                .filter(refund -> refund.getStatus() != RefundStatus.FAILED)
                .map(Refund::amount)
                .reduce(Money.zero(payment.amount().currency()), Money::plus);
        return payment.amount().minus(committed);
    }

    private static Payment replay(Payment existing, String fingerprint) {
        if (!existing.matchesRequest(fingerprint)) {
            throw new SkyBookException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was already used for a different payment");
        }
        return existing;
    }

    private Payment find(String paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
    }
}
