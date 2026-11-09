package com.skybook.booking.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.skybook.booking.application.port.in.CancelBookingUseCase;
import com.skybook.booking.application.port.in.ChangeBookingUseCase;
import com.skybook.booking.application.port.in.ManualRefundUseCase;
import com.skybook.booking.application.port.in.PaymentEventsUseCase;
import com.skybook.booking.application.port.in.SagaRecoveryUseCase;
import com.skybook.booking.application.port.out.BookingEventPublisher;
import com.skybook.booking.application.port.out.BookingRepository;
import com.skybook.booking.application.port.out.CustomerProfilePort;
import com.skybook.booking.application.port.out.InventoryPort;
import com.skybook.booking.application.port.out.LoyaltyPort;
import com.skybook.booking.application.port.out.PaymentPort;
import com.skybook.booking.application.port.out.TicketingPort;
import com.skybook.booking.domain.model.Booking;
import com.skybook.booking.domain.model.BookingErrorCode;
import com.skybook.booking.domain.model.BookingStatus;
import com.skybook.booking.domain.model.FlightSnapshot;
import com.skybook.booking.domain.model.Passenger;
import com.skybook.booking.domain.model.PendingChange;
import com.skybook.booking.domain.policy.ChangeFeePolicy;
import com.skybook.booking.domain.policy.RefundPolicy;
import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.error.ResourceNotFoundException;
import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;

/**
 * Orchestrated booking saga (ADR-0014): what happens after payment, cancellation and date change.
 *
 * <p>Every step is idempotent and driven by the booking's persisted status, so the saga can be resumed at
 * any point - by the payment event that triggered it, or by {@link #recoverStalledSagas} after a crash or
 * an outage. Remote calls happen outside database transactions; each local state change is saved
 * together with its domain event (outbox).
 *
 * <pre>
 * PAID                    -> issue tickets (GDS) -> confirm hold (inventory) -> CONFIRMED + TicketIssued
 *                            on permanent failure, or transient failure past the deadline:
 *                            void tickets, release hold, refund in full -> FAILED + BookingCancelled
 * CANCELLATION_PENDING    -> void tickets, release seats, refund per fare rules -> CANCELLED on RefundIssued
 * CHANGE_PENDING_PAYMENT  -> (paid) apply change; (hold expired unpaid) abandon change
 * </pre>
 */
@Service
public class BookingSagaService implements PaymentEventsUseCase, CancelBookingUseCase, ChangeBookingUseCase,
        ManualRefundUseCase, SagaRecoveryUseCase {

    /** Transient ticketing failures are retried for this long after payment, then the booking is refunded. */
    static final Duration TICKETING_DEADLINE = Duration.ofMinutes(10);
    /** Sagas idle for longer than this are resumed by the recovery job. */
    static final Duration STALL_THRESHOLD = Duration.ofSeconds(30);
    private static final int RECOVERY_BATCH = 50;
    private static final Logger LOG = LoggerFactory.getLogger(BookingSagaService.class);

    private final BookingRepository bookings;
    private final InventoryPort inventory;
    private final PaymentPort payments;
    private final TicketingPort ticketing;
    private final LoyaltyPort loyalty;
    private final CustomerProfilePort customers;
    private final BookingEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final RefundPolicy refundPolicy = new RefundPolicy();
    private final ChangeFeePolicy changeFeePolicy = new ChangeFeePolicy();

    @SuppressWarnings("checkstyle:ParameterNumber")
    public BookingSagaService(BookingRepository bookings, InventoryPort inventory, PaymentPort payments,
                              TicketingPort ticketing, LoyaltyPort loyalty, CustomerProfilePort customers,
                              BookingEventPublisher events, TransactionTemplate tx, Clock clock) {
        this.bookings = bookings;
        this.inventory = inventory;
        this.payments = payments;
        this.ticketing = ticketing;
        this.loyalty = loyalty;
        this.customers = customers;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------ payment events

    @Override
    public void recordPaymentCompleted(String bookingReference, String paymentId, String purpose, Money amount) {
        Booking booking = find(bookingReference);
        boolean changed = "DATE_CHANGE".equals(purpose)
                ? booking.markChangePaid(paymentId, clock.instant())
                : booking.markPaid(paymentId, amount, clock.instant());
        if (changed) {
            bookings.save(booking);
            LOG.info("Booking {}: {} payment {} received", bookingReference, purpose, paymentId);
        }
    }

    @Override
    public void recordRefundIssued(String bookingReference, Money amount) {
        Booking booking = find(bookingReference);
        booking.recordRefund(amount, clock.instant());
        if (booking.isCancellationRefundComplete()) {
            booking.markCancelled(clock.instant());
            events.bookingCancelled(booking, "CUSTOMER_REQUEST", booking.getCancellationRefund());
            LOG.info("Booking {} cancelled; refunded {}", bookingReference, booking.getAmountRefunded());
        }
        bookings.save(booking);
    }

    @Override
    public void resume(String bookingReference) {
        Booking booking = find(bookingReference);
        Instant now = clock.instant();
        try {
            switch (booking.getStatus()) {
                case PAID -> completeTicketing(booking, now);
                case CANCELLATION_PENDING -> continueCancellation(booking);
                case CHANGE_PENDING_PAYMENT -> continueChange(booking, now);
                default -> LOG.debug("Booking {} is {}; nothing to resume", bookingReference, booking.getStatus());
            }
        } catch (ConcurrencyFailureException ex) {
            LOG.info("Booking {} was advanced concurrently; this resume attempt stops here", bookingReference);
        }
    }

    // ------------------------------------------------------------------------------------ ticketing

    private void completeTicketing(Booking booking, Instant now) {
        String reference = booking.getReference().value();
        List<String> tickets = List.of();
        try {
            tickets = ticketing.issueTickets(reference, booking.getFlight(), booking.getPassengers());
            inventory.confirmHold(booking.getHoldId(), reference);
        } catch (SkyBookException ex) {
            boolean transientFailure = ex.getErrorCode() == CommonErrorCode.SERVICE_UNAVAILABLE;
            boolean pastDeadline = !booking.getUpdatedAt().plus(TICKETING_DEADLINE).isAfter(now);
            if (transientFailure && !pastDeadline) {
                LOG.warn("Ticketing booking {} failed transiently; the recovery job will retry: {}", reference,
                        ex.getMessage());
                return;
            }
            LOG.error("Ticketing booking {} failed ({}); compensating", reference, ex.getErrorCode().code());
            compensateTicketing(booking, tickets);
            return;
        }
        List<String> issued = tickets;
        Booking confirmed = tx.execute(status -> {
            Booking current = find(reference);
            current.confirm(issued, clock.instant());
            Booking saved = bookings.save(current);
            events.ticketIssued(saved);
            return saved;
        });
        LOG.info("Booking {} confirmed with tickets {}", reference, issued);
        loyalty.accrueMiles(confirmed.getCustomerId(), reference, confirmed.getAmountPaid());
    }

    /** Undo everything a failed ticketing attempt touched, refund in full, and mark the booking FAILED. */
    private void compensateTicketing(Booking booking, List<String> issuedTickets) {
        String reference = booking.getReference().value();
        if (!issuedTickets.isEmpty()) {
            bestEffort(() -> ticketing.voidTickets(reference, issuedTickets), "void tickets of " + reference);
        }
        bestEffort(() -> inventory.releaseHold(booking.getHoldId()), "release hold of " + reference);
        // Must succeed: if payment-service is down this throws and the recovery job compensates again later.
        payments.refundBooking(reference, booking.refundableAmount(), PaymentPort.RefundReason.TICKETING_FAILED,
                "compensate-" + reference);
        tx.executeWithoutResult(status -> {
            Booking current = find(reference);
            current.fail(clock.instant());
            Booking saved = bookings.save(current);
            events.bookingCancelled(saved, "TICKETING_FAILED", saved.refundableAmount());
        });
    }

    // ------------------------------------------------------------------------------------ cancellation

    @Override
    public RefundPolicy.Quote quoteCancellation(String reference, String callerId, boolean callerIsStaff) {
        Booking booking = findVisible(reference, callerId, callerIsStaff);
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new SkyBookException(BookingErrorCode.NOT_CANCELLABLE,
                    "Booking %s is %s and cannot be cancelled".formatted(reference, booking.getStatus()));
        }
        return refundPolicy.quote(booking, clock.instant());
    }

    @Override
    public Booking cancel(String reference, String callerId, boolean callerIsStaff) {
        Booking booking = findVisible(reference, callerId, callerIsStaff);
        RefundPolicy.Quote quote = refundPolicy.quote(booking, clock.instant());
        booking.requestCancellation(quote.amount(), clock.instant());
        bookings.save(booking);
        LOG.info("Cancellation of booking {} requested; refund due {}", reference, quote.amount());
        resume(reference);
        return find(reference);
    }

    private void continueCancellation(Booking booking) {
        String reference = booking.getReference().value();
        bestEffort(() -> ticketing.voidTickets(reference, booking.ticketNumbers()), "void tickets of " + reference);
        inventory.releaseBookedSeats(booking.getFlight().flightId(), reference);
        Money refund = booking.getCancellationRefund();
        if (refund.isPositive()) {
            payments.refundBooking(reference, refund, PaymentPort.RefundReason.CUSTOMER_CANCELLATION,
                    "cancel-" + reference);
            return;
        }
        tx.executeWithoutResult(status -> {
            Booking current = find(reference);
            current.markCancelled(clock.instant());
            Booking saved = bookings.save(current);
            events.bookingCancelled(saved, "CUSTOMER_REQUEST", refund);
        });
        LOG.info("Booking {} cancelled without refund (fare rules)", reference);
    }

    // ------------------------------------------------------------------------------------ date change

    @Override
    public Booking changeDate(ChangeDateCommand command) {
        Booking booking = findVisible(command.reference(), command.callerId(), command.callerIsStaff());
        booking.ensureChangeable(clock.instant());
        if (command.newFlightId().equals(booking.getFlight().flightId())) {
            throw new SkyBookException(BookingErrorCode.NOT_CHANGEABLE,
                    "The new flight must differ from the current one; seat changes are not date changes");
        }
        if (command.seatNumbers().size() != booking.getPassengers().size()) {
            throw new SkyBookException(CommonErrorCode.VALIDATION_FAILED,
                    "Exactly one seat per passenger is required (%d)".formatted(booking.getPassengers().size()));
        }
        InventoryPort.SeatHold hold = inventory.holdSeats(command.newFlightId(), command.seatNumbers(),
                "chg-" + sha256(command.reference() + ":" + command.idempotencyKey()).substring(0, 40));
        List<PendingChange.SeatAssignment> seats = command.seatNumbers().stream()
                .map(seat -> new PendingChange.SeatAssignment(seat, hold.priceOf(seat)))
                .toList();
        Money fee = changeFeePolicy.feeFor(booking.getPassengers().size(), customers.tierOf(booking.getCustomerId()),
                booking.getTotalPrice().currency());
        Money due = hold.total().plus(fee).minus(booking.getTotalPrice());
        String reference = booking.getReference().value();

        if (due.isPositive()) {
            PaymentPort.PaymentIntent intent = payments.createPayment(reference, due,
                    PaymentPort.Purpose.DATE_CHANGE, "chg-pay-" + hold.holdId());
            booking.startPaidChange(new PendingChange(hold.flight(), hold.holdId(), hold.expiresAt(), seats, due,
                    intent.paymentId(), false), intent.clientSecret(), clock.instant());
            LOG.info("Date change of booking {} to flight {} awaits payment of {}", reference,
                    hold.flight().flightNumber(), due);
            return bookings.save(booking);
        }
        applyChange(booking, hold.flight(), hold.holdId(), seats, Money.zero(due.currency()));
        if (due.isNegative()) {
            payments.refundBooking(reference, due.negate(), PaymentPort.RefundReason.DATE_CHANGE_DIFFERENCE,
                    "chg-refund-" + hold.holdId());
        }
        return find(reference);
    }

    private void continueChange(Booking booking, Instant now) {
        PendingChange change = booking.getPendingChange();
        String reference = booking.getReference().value();
        if (change.paid()) {
            try {
                applyChange(booking, change.newFlight(), change.holdId(), change.newSeats(), change.amountDue());
            } catch (SkyBookException ex) {
                if (ex.getErrorCode() == CommonErrorCode.SERVICE_UNAVAILABLE) {
                    throw ex;
                }
                LOG.error("Paid date change of booking {} could not be applied ({}); refunding the difference",
                        reference, ex.getErrorCode().code());
                payments.refundBooking(reference, change.amountDue(), PaymentPort.RefundReason.DATE_CHANGE_DIFFERENCE,
                        "chg-failed-" + change.holdId());
                abandonChange(reference);
            }
        } else if (booking.isPendingChangeOverdue(now)) {
            LOG.info("Date change of booking {} expired unpaid; keeping the original flight", reference);
            bestEffort(() -> payments.cancelPayment(change.paymentId()), "cancel change payment of " + reference);
            bestEffort(() -> inventory.releaseHold(change.holdId()), "release change hold of " + reference);
            abandonChange(reference);
        }
    }

    private void applyChange(Booking booking, FlightSnapshot newFlight, String newHoldId,
                             List<PendingChange.SeatAssignment> seats, Money additionalPayment) {
        String reference = booking.getReference().value();
        List<String> oldTickets = booking.ticketNumbers();
        List<Passenger> moved = new ArrayList<>();
        for (int i = 0; i < booking.getPassengers().size(); i++) {
            moved.add(booking.getPassengers().get(i).withSeat(seats.get(i).seatNumber(), seats.get(i).price()));
        }
        List<String> newTickets = ticketing.issueTickets(reference, newFlight, moved);
        inventory.confirmHold(newHoldId, reference);
        FlightSnapshot previous = tx.execute(status -> {
            Booking current = find(reference);
            FlightSnapshot old = current.applyChange(newFlight, newHoldId, seats, additionalPayment, newTickets,
                    clock.instant());
            Booking saved = bookings.save(current);
            events.bookingChanged(saved, old);
            return old;
        });
        bestEffort(() -> inventory.releaseBookedSeats(previous.flightId(), reference),
                "release old seats of " + reference);
        bestEffort(() -> ticketing.voidTickets(reference, oldTickets), "void old tickets of " + reference);
        LOG.info("Booking {} moved from flight {} to {}", reference, previous.flightNumber(), newFlight.flightNumber());
    }

    private void abandonChange(String reference) {
        tx.executeWithoutResult(status -> {
            Booking current = find(reference);
            current.abandonChange(clock.instant());
            bookings.save(current);
        });
    }

    // ------------------------------------------------------------------------------------ manual refunds

    @Override
    public Booking refund(String reference, Money amount, String note, String idempotencyKey) {
        Booking booking = find(reference);
        if (!amount.isPositive() || amount.isGreaterThan(booking.refundableAmount())) {
            throw new SkyBookException(BookingErrorCode.REFUND_EXCEEDS_PAYMENT,
                    "Refund must be between 0 and %s".formatted(booking.refundableAmount()));
        }
        payments.refundBooking(reference, amount, PaymentPort.RefundReason.MANUAL, "manual-" + idempotencyKey);
        LOG.info("Manual refund of {} requested for booking {}: {}", amount, reference, note);
        return booking;
    }

    // ------------------------------------------------------------------------------------ recovery

    @Override
    public int recoverStalledSagas(Instant now) {
        int looked = 0;
        for (BookingStatus status : List.of(BookingStatus.PAID, BookingStatus.CANCELLATION_PENDING,
                BookingStatus.CHANGE_PENDING_PAYMENT)) {
            for (String reference : bookings.findNotUpdatedSince(status, now.minus(STALL_THRESHOLD), RECOVERY_BATCH)) {
                looked++;
                try {
                    resume(reference);
                } catch (RuntimeException ex) {
                    LOG.warn("Resuming the saga of booking {} failed; will retry: {}", reference, ex.toString());
                }
            }
        }
        return looked;
    }

    // ------------------------------------------------------------------------------------ helpers

    private Booking find(String reference) {
        return bookings.findByReference(reference).orElseThrow(() -> new ResourceNotFoundException("Booking",
                reference));
    }

    private Booking findVisible(String reference, String callerId, boolean callerIsStaff) {
        return bookings.findByReference(reference)
                .filter(booking -> callerIsStaff || booking.isOwnedBy(callerId))
                .orElseThrow(() -> new ResourceNotFoundException("Booking", reference));
    }

    private static void bestEffort(Runnable action, String description) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            LOG.warn("Could not {} (will not block the saga): {}", description, ex.toString());
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
