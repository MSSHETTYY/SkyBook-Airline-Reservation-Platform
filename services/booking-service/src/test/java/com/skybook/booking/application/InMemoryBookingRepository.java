package com.skybook.booking.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.skybook.booking.application.port.in.PageResult;
import com.skybook.booking.application.port.out.BookingRepository;
import com.skybook.booking.domain.model.Booking;
import com.skybook.booking.domain.model.BookingState;
import com.skybook.booking.domain.model.BookingStatus;

/** Stateful test double with the same optimistic-locking contract as the JPA adapter. */
class InMemoryBookingRepository implements BookingRepository {

    private final Map<String, BookingState> rows = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public synchronized Booking save(Booking booking) {
        BookingState state = booking.state();
        BookingState existing = rows.get(state.reference().value());
        if (existing != null && existing.version() != state.version()) {
            throw new ObjectOptimisticLockingFailureException(Booking.class, state.id());
        }
        Long id = state.id() == null ? ids.incrementAndGet() : state.id();
        BookingState saved = new BookingState(id, state.reference(), state.customerId(), state.contactEmail(),
                state.status(), state.flight(), state.passengers(), state.totalPrice(), state.holdId(),
                state.holdExpiresAt(), state.paymentId(), state.paymentClientSecret(), state.amountPaid(),
                state.amountRefunded(), state.cancellationRefund(), state.pendingChange(), state.idempotencyKey(),
                state.requestFingerprint(), state.createdAt(), state.updatedAt(), state.version() + 1);
        rows.put(state.reference().value(), saved);
        return Booking.restore(saved);
    }

    @Override
    public Optional<Booking> findByReference(String reference) {
        return Optional.ofNullable(rows.get(reference)).map(Booking::restore);
    }

    @Override
    public Optional<Booking> findByCustomerAndIdempotencyKey(String customerId, String idempotencyKey) {
        return rows.values().stream()
                .filter(s -> s.customerId().equals(customerId) && Objects.equals(s.idempotencyKey(), idempotencyKey))
                .findFirst().map(Booking::restore);
    }

    @Override
    public PageResult<Booking> findByCustomer(String customerId, int page, int size) {
        List<Booking> all = rows.values().stream().filter(s -> s.customerId().equals(customerId))
                .map(Booking::restore).toList();
        return new PageResult<>(all, page, size, all.size());
    }

    @Override
    public PageResult<Booking> search(BookingStatus status, Long flightId, int page, int size) {
        List<Booking> all = rows.values().stream().map(Booking::restore).toList();
        return new PageResult<>(all, page, size, all.size());
    }

    @Override
    public List<String> findWithHoldExpiredBefore(BookingStatus status, Instant now, int limit) {
        return rows.values().stream()
                .filter(s -> s.status() == status && s.holdExpiresAt() != null && !s.holdExpiresAt().isAfter(now))
                .map(s -> s.reference().value()).limit(limit).toList();
    }

    @Override
    public List<String> findNotUpdatedSince(BookingStatus status, Instant updatedBefore, int limit) {
        return rows.values().stream()
                .filter(s -> s.status() == status && !s.updatedAt().isAfter(updatedBefore))
                .sorted(Comparator.comparing(BookingState::updatedAt))
                .map(s -> s.reference().value()).limit(limit).toList();
    }
}
