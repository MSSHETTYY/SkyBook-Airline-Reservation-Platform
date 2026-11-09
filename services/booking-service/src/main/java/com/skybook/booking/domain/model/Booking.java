package com.skybook.booking.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;

/**
 * Booking aggregate root: one customer's reservation of seats on one flight, from seat hold to ticket,
 * date change or refund.
 *
 * <p>Invariants enforced here: every passenger has a distinct seat; the total equals the sum of seat
 * prices; status changes follow {@link BookingStatus#allowedNext()}; refunds never exceed payments.
 * The aggregate is framework-free (no JPA, no Spring) and is persisted through {@link BookingState}.
 */
public final class Booking {

    private Long id;
    private final BookingReference reference;
    private final String customerId;
    private final String contactEmail;
    private BookingStatus status;
    private FlightSnapshot flight;
    private List<Passenger> passengers;
    private Money totalPrice;
    private String holdId;
    private Instant holdExpiresAt;
    private String paymentId;
    private String paymentClientSecret;
    private Money amountPaid;
    private Money amountRefunded;
    private Money cancellationRefund;
    private PendingChange pendingChange;
    private final String idempotencyKey;
    private final String requestFingerprint;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private Booking(BookingState s) {
        this.id = s.id();
        this.reference = Objects.requireNonNull(s.reference(), "reference");
        this.customerId = Objects.requireNonNull(s.customerId(), "customerId");
        this.contactEmail = Objects.requireNonNull(s.contactEmail(), "contactEmail");
        this.status = Objects.requireNonNull(s.status(), "status");
        this.flight = Objects.requireNonNull(s.flight(), "flight");
        this.passengers = List.copyOf(s.passengers());
        this.totalPrice = Objects.requireNonNull(s.totalPrice(), "totalPrice");
        this.holdId = s.holdId();
        this.holdExpiresAt = s.holdExpiresAt();
        this.paymentId = s.paymentId();
        this.paymentClientSecret = s.paymentClientSecret();
        this.amountPaid = s.amountPaid() == null ? Money.zero(totalPrice.currency()) : s.amountPaid();
        this.amountRefunded = s.amountRefunded() == null ? Money.zero(totalPrice.currency()) : s.amountRefunded();
        this.cancellationRefund = s.cancellationRefund();
        this.pendingChange = s.pendingChange();
        this.idempotencyKey = s.idempotencyKey();
        this.requestFingerprint = s.requestFingerprint();
        this.createdAt = Objects.requireNonNull(s.createdAt(), "createdAt");
        this.updatedAt = s.updatedAt() == null ? s.createdAt() : s.updatedAt();
        this.version = s.version();
    }

    /** Creates a booking for seats that inventory has just held for us. */
    public static Booking create(BookingReference reference, String customerId, String contactEmail,
                                 FlightSnapshot flight, List<Passenger> passengers, String holdId,
                                 Instant holdExpiresAt, String idempotencyKey, String requestFingerprint, Instant now) {
        if (passengers.isEmpty()) {
            throw new IllegalArgumentException("A booking needs at least one passenger");
        }
        requireDistinctSeats(passengers);
        Money total = passengers.stream().map(Passenger::seatPrice).reduce(Money::plus).orElseThrow();
        return new Booking(new BookingState(null, reference, customerId, contactEmail, BookingStatus.PENDING_PAYMENT,
                flight, passengers, total, Objects.requireNonNull(holdId, "holdId"),
                Objects.requireNonNull(holdExpiresAt, "holdExpiresAt"), null, null, null, null, null, null,
                idempotencyKey, requestFingerprint, now, now, 0));
    }

    public static Booking restore(BookingState state) {
        return new Booking(state);
    }

    public BookingState state() {
        return new BookingState(id, reference, customerId, contactEmail, status, flight, passengers, totalPrice, holdId,
                holdExpiresAt, paymentId, paymentClientSecret, amountPaid, amountRefunded, cancellationRefund,
                pendingChange, idempotencyKey, requestFingerprint, createdAt, updatedAt, version);
    }

    /** Assigns the database id after the first save (identity column). */
    public void assignId(Long newId) {
        if (this.id != null && !this.id.equals(newId)) {
            throw new IllegalStateException("Booking id already assigned");
        }
        this.id = newId;
    }

    // ------------------------------------------------------------------ lifecycle: payment and expiry

    /** Whether payment is overdue: the seat hold has run out and no payment arrived. */
    public boolean isPaymentOverdue(Instant now) {
        return status == BookingStatus.PENDING_PAYMENT && !holdExpiresAt.isAfter(now);
    }

    public void expire(Instant now) {
        if (!isPaymentOverdue(now)) {
            throw invalidState("expire");
        }
        transitionTo(BookingStatus.EXPIRED, now);
        paymentClientSecret = null;
    }

    /** Records the payment intent the customer must complete (Stripe client secret for Stripe Elements). */
    public void attachPayment(String newPaymentId, String clientSecret, Instant now) {
        if (status != BookingStatus.PENDING_PAYMENT) {
            throw invalidState("attach a payment to");
        }
        this.paymentId = Objects.requireNonNull(newPaymentId, "paymentId");
        this.paymentClientSecret = clientSecret;
        this.updatedAt = now;
    }

    /**
     * Payment for the booking succeeded. Idempotent: a duplicate notification for the same payment is
     * ignored and returns {@code false}.
     */
    public boolean markPaid(String paidPaymentId, Money amount, Instant now) {
        if (status != BookingStatus.PENDING_PAYMENT) {
            if (Objects.equals(paidPaymentId, paymentId)) {
                return false;
            }
            throw invalidState("record a payment for");
        }
        if (!Objects.equals(paidPaymentId, paymentId)) {
            throw new SkyBookException(BookingErrorCode.INVALID_STATE,
                    "Payment %s does not belong to booking %s".formatted(paidPaymentId, reference));
        }
        amountPaid = amountPaid.plus(amount);
        paymentClientSecret = null;
        transitionTo(BookingStatus.PAID, now);
        return true;
    }

    /** Tickets were issued and seats confirmed in inventory. */
    public void confirm(List<String> ticketNumbers, Instant now) {
        if (status != BookingStatus.PAID) {
            throw invalidState("confirm");
        }
        if (ticketNumbers.size() != passengers.size()) {
            throw new IllegalArgumentException("Expected %d ticket numbers but got %d"
                    .formatted(passengers.size(), ticketNumbers.size()));
        }
        List<Passenger> ticketed = new ArrayList<>(passengers.size());
        for (int i = 0; i < passengers.size(); i++) {
            ticketed.add(passengers.get(i).withTicket(ticketNumbers.get(i)));
        }
        passengers = List.copyOf(ticketed);
        holdExpiresAt = null;
        transitionTo(BookingStatus.CONFIRMED, now);
    }

    /** Ticketing failed after payment: the saga compensates (refund, release seats) and records it here. */
    public void fail(Instant now) {
        if (status != BookingStatus.PAID) {
            throw invalidState("fail");
        }
        transitionTo(BookingStatus.FAILED, now);
    }

    // ------------------------------------------------------------------ lifecycle: cancellation and refunds

    /**
     * Starts cancelling a confirmed booking. The refund promised now (per the fare rules at request time) is
     * stored, so the amount cannot change while the refund is processed asynchronously.
     */
    public void requestCancellation(Money refund, Instant now) {
        if (refund.isNegative() || refund.isGreaterThan(refundableAmount())) {
            throw new IllegalArgumentException("Cancellation refund must be between 0 and " + refundableAmount());
        }
        if (status != BookingStatus.CONFIRMED) {
            throw new SkyBookException(BookingErrorCode.NOT_CANCELLABLE,
                    "Booking %s is %s and cannot be cancelled".formatted(reference, status));
        }
        if (!flight.departureTime().isAfter(now)) {
            throw new SkyBookException(BookingErrorCode.NOT_CANCELLABLE,
                    "Booking %s has already departed".formatted(reference));
        }
        cancellationRefund = refund;
        transitionTo(BookingStatus.CANCELLATION_PENDING, now);
    }

    /** Whether all of the promised cancellation refund has been confirmed by payment-service. */
    public boolean isCancellationRefundComplete() {
        return status == BookingStatus.CANCELLATION_PENDING
                && cancellationRefund != null && !cancellationRefund.isGreaterThan(amountRefunded);
    }

    public void markCancelled(Instant now) {
        transitionTo(BookingStatus.CANCELLED, now);
    }

    /** Cancels a booking that was never paid (customer abandons checkout). */
    public void cancelUnpaid(Instant now) {
        if (status != BookingStatus.PENDING_PAYMENT) {
            throw invalidState("cancel an unpaid");
        }
        paymentClientSecret = null;
        transitionTo(BookingStatus.CANCELLED, now);
    }

    /** Records money returned to the customer; refunds can never exceed what was paid. */
    public void recordRefund(Money amount, Instant now) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }
        if (amountRefunded.plus(amount).isGreaterThan(amountPaid)) {
            throw new SkyBookException(BookingErrorCode.REFUND_EXCEEDS_PAYMENT,
                    "Refund of %s would exceed the %s paid (already refunded %s)"
                            .formatted(amount, amountPaid, amountRefunded));
        }
        amountRefunded = amountRefunded.plus(amount);
        updatedAt = now;
    }

    public Money refundableAmount() {
        return amountPaid.minus(amountRefunded);
    }

    // ------------------------------------------------------------------ lifecycle: date change

    /**
     * Starts a date change that requires the customer to pay {@code change.amountDue()} first.
     *
     * @param clientSecret Stripe client secret of the change payment, shown to the customer until paid
     */
    public void startPaidChange(PendingChange change, String clientSecret, Instant now) {
        requireChangeable(now);
        if (!change.amountDue().isPositive()) {
            throw new IllegalArgumentException("A paid change needs a positive amount due");
        }
        pendingChange = change;
        paymentClientSecret = clientSecret;
        transitionTo(BookingStatus.CHANGE_PENDING_PAYMENT, now);
    }

    /** The fare difference of the pending change was paid. Idempotent; returns whether anything changed. */
    public boolean markChangePaid(String changePaymentId, Instant now) {
        if (status != BookingStatus.CHANGE_PENDING_PAYMENT || !changePaymentId.equals(pendingChange.paymentId())) {
            throw invalidState("record a change payment for");
        }
        if (pendingChange.paid()) {
            return false;
        }
        pendingChange = pendingChange.markPaid();
        paymentClientSecret = null;
        updatedAt = now;
        return true;
    }

    /** Throws unless the booking may be moved to another flight at {@code now}. */
    public void ensureChangeable(Instant now) {
        requireChangeable(now);
    }

    /** Whether a pending change was abandoned by the customer: its hold ran out before it was paid. */
    public boolean isPendingChangeOverdue(Instant now) {
        return status == BookingStatus.CHANGE_PENDING_PAYMENT && !pendingChange.paid()
                && !pendingChange.holdExpiresAt().isAfter(now);
    }

    /**
     * Applies a date change: moves passengers to the new flight and seats, re-prices the booking and
     * records any extra payment. Returns the previous flight so the caller can release its seats.
     */
    public FlightSnapshot applyChange(FlightSnapshot newFlight, String newHoldId,
                                      List<PendingChange.SeatAssignment> seats, Money additionalPayment,
                                      List<String> newTicketNumbers, Instant now) {
        if (status == BookingStatus.CONFIRMED) {
            requireChangeable(now);
        } else if (status != BookingStatus.CHANGE_PENDING_PAYMENT) {
            throw invalidState("change");
        }
        if (seats.size() != passengers.size() || newTicketNumbers.size() != passengers.size()) {
            throw new IllegalArgumentException("One seat and one ticket per passenger required");
        }
        List<Passenger> moved = new ArrayList<>(passengers.size());
        for (int i = 0; i < passengers.size(); i++) {
            PendingChange.SeatAssignment seat = seats.get(i);
            moved.add(passengers.get(i).withSeat(seat.seatNumber(), seat.price()).withTicket(newTicketNumbers.get(i)));
        }
        requireDistinctSeats(moved);
        FlightSnapshot previous = flight;
        flight = newFlight;
        passengers = List.copyOf(moved);
        totalPrice = moved.stream().map(Passenger::seatPrice).reduce(Money::plus).orElseThrow();
        holdId = newHoldId;
        if (additionalPayment.isPositive()) {
            amountPaid = amountPaid.plus(additionalPayment);
        }
        pendingChange = null;
        paymentClientSecret = null;
        transitionTo(BookingStatus.CONFIRMED, now);
        return previous;
    }

    /** Abandons a pending change (its payment failed or its hold expired); the original booking stands. */
    public PendingChange abandonChange(Instant now) {
        if (status != BookingStatus.CHANGE_PENDING_PAYMENT) {
            throw invalidState("abandon a change on");
        }
        PendingChange abandoned = pendingChange;
        pendingChange = null;
        paymentClientSecret = null;
        status = BookingStatus.CONFIRMED;
        updatedAt = now;
        return abandoned;
    }

    private void requireChangeable(Instant now) {
        if (status != BookingStatus.CONFIRMED) {
            throw new SkyBookException(BookingErrorCode.NOT_CHANGEABLE,
                    "Booking %s is %s and cannot be changed".formatted(reference, status));
        }
        if (!flight.departureTime().isAfter(now)) {
            throw new SkyBookException(BookingErrorCode.NOT_CHANGEABLE,
                    "Booking %s has already departed".formatted(reference));
        }
    }

    // ------------------------------------------------------------------ queries

    public boolean isOwnedBy(String candidateCustomerId) {
        return customerId.equals(candidateCustomerId);
    }

    public List<String> seatNumbers() {
        return passengers.stream().map(Passenger::seatNumber).toList();
    }

    public boolean matchesRequest(String fingerprint) {
        return Objects.equals(requestFingerprint, fingerprint);
    }

    private void transitionTo(BookingStatus next, Instant now) {
        if (!status.allowedNext().contains(next)) {
            throw new SkyBookException(BookingErrorCode.INVALID_STATE,
                    "Booking %s cannot move from %s to %s".formatted(reference, status, next));
        }
        status = next;
        updatedAt = now;
    }

    private SkyBookException invalidState(String action) {
        return new SkyBookException(BookingErrorCode.INVALID_STATE,
                "Cannot %s booking %s in status %s".formatted(action, reference, status));
    }

    private static void requireDistinctSeats(List<Passenger> passengers) {
        Set<String> seen = new HashSet<>();
        for (Passenger passenger : passengers) {
            if (!seen.add(passenger.seatNumber())) {
                throw new SkyBookException(BookingErrorCode.DUPLICATE_SEAT,
                        "Seat %s is assigned to more than one passenger".formatted(passenger.seatNumber()));
            }
        }
    }

    public Long getId() {
        return id;
    }

    public BookingReference getReference() {
        return reference;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public FlightSnapshot getFlight() {
        return flight;
    }

    public List<Passenger> getPassengers() {
        return passengers;
    }

    public Money getTotalPrice() {
        return totalPrice;
    }

    public String getHoldId() {
        return holdId;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getPaymentClientSecret() {
        return paymentClientSecret;
    }

    public Money getAmountPaid() {
        return amountPaid;
    }

    public Money getAmountRefunded() {
        return amountRefunded;
    }

    public PendingChange getPendingChange() {
        return pendingChange;
    }

    public Money getCancellationRefund() {
        return cancellationRefund;
    }

    public List<String> ticketNumbers() {
        return passengers.stream().map(Passenger::ticketNumber).filter(Objects::nonNull).toList();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
