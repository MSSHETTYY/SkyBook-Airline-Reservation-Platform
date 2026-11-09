package com.skybook.booking.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.skybook.booking.application.port.in.BookingQueries;
import com.skybook.booking.application.port.in.CreateBookingUseCase;
import com.skybook.booking.application.port.in.ExpireUnpaidBookingsUseCase;
import com.skybook.booking.application.port.in.PageResult;
import com.skybook.booking.application.port.out.BookingEventPublisher;
import com.skybook.booking.application.port.out.BookingRepository;
import com.skybook.booking.application.port.out.BookingRepository.DuplicateBookingException;
import com.skybook.booking.application.port.out.InventoryPort;
import com.skybook.booking.application.port.out.PaymentPort;
import com.skybook.booking.domain.model.Booking;
import com.skybook.booking.domain.model.BookingErrorCode;
import com.skybook.booking.domain.model.BookingReference;
import com.skybook.booking.domain.model.BookingStatus;
import com.skybook.booking.domain.model.Passenger;
import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.error.ResourceNotFoundException;
import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;

/**
 * Booking creation, queries and expiry of unpaid bookings.
 *
 * <p>Remote calls (inventory) are deliberately made <em>outside</em> database transactions, so a slow
 * downstream service never holds a database connection or row lock. Consistency between the two
 * systems is kept by idempotent operations and compensation (release the hold if the booking cannot be
 * stored), with the 15-minute hold expiry as the final safety net.
 */
@Service
public class BookingService implements CreateBookingUseCase, BookingQueries, ExpireUnpaidBookingsUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(BookingService.class);
    private static final int MAX_REFERENCE_ATTEMPTS = 5;
    private static final int EXPIRY_BATCH = 100;

    private final BookingRepository bookings;
    private final InventoryPort inventory;
    private final PaymentPort payments;
    private final BookingEventPublisher events;
    private final TransactionTemplate tx;
    private final RandomGenerator random;
    private final Clock clock;

    public BookingService(BookingRepository bookings, InventoryPort inventory, PaymentPort payments,
                          BookingEventPublisher events, TransactionTemplate tx, RandomGenerator random, Clock clock) {
        this.bookings = bookings;
        this.inventory = inventory;
        this.payments = payments;
        this.events = events;
        this.tx = tx;
        this.random = random;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------- create

    @Override
    public Booking create(CreateBookingCommand command) {
        String fingerprint = fingerprint(command);
        var existing = bookings.findByCustomerAndIdempotencyKey(command.customerId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return ensurePayment(replay(existing.get(), fingerprint));
        }
        List<String> seatNumbers = command.passengers().stream().map(PassengerDetails::seatNumber).toList();
        requireDistinct(seatNumbers);

        // Same customer + key => same inventory key, so a retried request reuses the same hold.
        InventoryPort.SeatHold hold = inventory.holdSeats(command.flightId(), seatNumbers, inventoryKey(command));
        try {
            Booking saved = saveWithFreshReference(command, hold, fingerprint);
            LOG.info("Booking {} created for flight {} with {} passenger(s); payment due by {}",
                    saved.getReference(), hold.flight().flightNumber(), seatNumbers.size(), hold.expiresAt());
            return ensurePayment(saved);
        } catch (DuplicateBookingException ex) {
            // A concurrent request with the same idempotency key won; it owns the (shared) hold.
            return bookings.findByCustomerAndIdempotencyKey(command.customerId(), command.idempotencyKey())
                    .map(winner -> ensurePayment(replay(winner, fingerprint)))
                    .orElseThrow(() -> ex);
        } catch (RuntimeException ex) {
            releaseQuietly(hold.holdId());
            throw ex;
        }
    }

    private Booking saveWithFreshReference(CreateBookingCommand command, InventoryPort.SeatHold hold,
                                           String fingerprint) {
        Instant now = clock.instant();
        List<Passenger> passengers = command.passengers().stream()
                .map(p -> new Passenger(p.firstName(), p.lastName(), p.dateOfBirth(), p.seatNumber(),
                        hold.priceOf(p.seatNumber()), null))
                .toList();
        for (int attempt = 1; ; attempt++) {
            Booking booking = Booking.create(BookingReference.random(random), command.customerId(),
                    command.contactEmail(), hold.flight(), passengers, hold.holdId(), hold.expiresAt(),
                    command.idempotencyKey(), fingerprint, now);
            try {
                // The booking and its first events commit together (transactional outbox).
                return tx.execute(status -> {
                    Booking saved = bookings.save(booking);
                    events.bookingCreated(saved);
                    events.seatsHeld(saved);
                    return saved;
                });
            } catch (DuplicateBookingException ex) {
                if (ex.isDuplicateIdempotencyKey() || attempt >= MAX_REFERENCE_ATTEMPTS) {
                    throw ex;
                }
                LOG.warn("Booking reference collision on attempt {}; generating a new reference", attempt);
            }
        }
    }

    /**
     * Attaches the payment the customer completes in the browser. Graceful degradation: if payment-service
     * is down the booking is still returned (seats stay held); the client retries the same request
     * (same Idempotency-Key) and the payment is attached then. The payment key is derived from the booking
     * reference, so repeated attempts never create a second payment.
     */
    private Booking ensurePayment(Booking booking) {
        if (booking.getPaymentId() != null || booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            return booking;
        }
        try {
            PaymentPort.PaymentIntent intent = payments.createPayment(booking.getReference().value(),
                    booking.getTotalPrice(), PaymentPort.Purpose.BOOKING, "booking-" + booking.getReference());
            booking.attachPayment(intent.paymentId(), intent.clientSecret(), clock.instant());
            return bookings.save(booking);
        } catch (SkyBookException ex) {
            LOG.warn("Booking {} created but its payment could not be set up yet: {}", booking.getReference(),
                    ex.getMessage());
            return booking;
        }
    }

    private static Booking replay(Booking existing, String fingerprint) {
        if (!existing.matchesRequest(fingerprint)) {
            throw new SkyBookException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was already used for a different booking request");
        }
        return existing;
    }

    // ------------------------------------------------------------------------------------- queries

    @Override
    public Booking get(String reference, String callerId, boolean callerIsStaff) {
        return bookings.findByReference(reference)
                .filter(booking -> callerIsStaff || booking.isOwnedBy(callerId))
                .orElseThrow(() -> new ResourceNotFoundException("Booking", reference));
    }

    @Override
    public PageResult<Booking> listForCustomer(String customerId, int page, int size) {
        return bookings.findByCustomer(customerId, page, size);
    }

    @Override
    public PageResult<Booking> search(BookingStatus status, Long flightId, int page, int size) {
        return bookings.search(status, flightId, page, size);
    }

    // ------------------------------------------------------------------------------------- expiry

    @Override
    public int expireOverdue(Instant now) {
        int expired = 0;
        for (String reference : bookings.findWithHoldExpiredBefore(BookingStatus.PENDING_PAYMENT, now, EXPIRY_BATCH)) {
            try {
                Booking booking = bookings.findByReference(reference).orElse(null);
                if (booking == null || !booking.isPaymentOverdue(now)) {
                    continue;
                }
                booking.expire(now);
                tx.executeWithoutResult(status -> {
                    bookings.save(booking);
                    events.bookingCancelled(booking, "PAYMENT_TIMEOUT", Money.zero(booking.getTotalPrice().currency()));
                });
                releaseQuietly(booking.getHoldId());
                if (booking.getPaymentId() != null) {
                    cancelPaymentQuietly(booking.getPaymentId());
                }
                expired++;
                LOG.info("Booking {} expired: not paid before the seat hold ran out", reference);
            } catch (ConcurrencyFailureException ex) {
                LOG.info("Booking {} changed while expiring (probably paid just in time); skipping", reference);
            }
        }
        return expired;
    }

    // ------------------------------------------------------------------------------------- helpers

    /** Best effort: if this fails the hold simply expires in inventory after 15 minutes. */
    private void releaseQuietly(String holdId) {
        try {
            inventory.releaseHold(holdId);
        } catch (RuntimeException ex) {
            LOG.warn("Could not release hold {}; it will expire on its own: {}", holdId, ex.toString());
        }
    }

    private void cancelPaymentQuietly(String paymentId) {
        try {
            payments.cancelPayment(paymentId);
        } catch (RuntimeException ex) {
            LOG.warn("Could not cancel payment {}; it stays unpaid: {}", paymentId, ex.toString());
        }
    }

    private static void requireDistinct(List<String> seatNumbers) {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = seatNumbers.stream().filter(seat -> !seen.add(seat)).distinct().toList();
        if (!duplicates.isEmpty()) {
            throw new SkyBookException(BookingErrorCode.DUPLICATE_SEAT,
                    "Seats requested more than once: " + String.join(", ", duplicates));
        }
    }

    /** Inventory keys are global and limited to 64 chars, so derive one from customer + client key. */
    static String inventoryKey(CreateBookingCommand command) {
        return "bk-" + sha256Base64Url(command.customerId() + ":" + command.idempotencyKey()).substring(0, 43);
    }

    /** Canonical digest of the request, used to detect reuse of an idempotency key for a different request. */
    static String fingerprint(CreateBookingCommand command) {
        String passengers = command.passengers().stream()
                .map(p -> String.join("|", p.firstName(), p.lastName(), String.valueOf(p.dateOfBirth()),
                        p.seatNumber()))
                .collect(Collectors.joining(";"));
        return sha256Base64Url(command.flightId() + "#" + command.contactEmail() + "#" + passengers);
    }

    private static String sha256Base64Url(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
