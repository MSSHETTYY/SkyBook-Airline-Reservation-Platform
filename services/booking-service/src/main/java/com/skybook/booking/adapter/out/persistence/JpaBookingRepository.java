package com.skybook.booking.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.skybook.booking.application.port.in.PageResult;
import com.skybook.booking.application.port.out.BookingRepository;
import com.skybook.booking.domain.model.Booking;
import com.skybook.booking.domain.model.BookingState;
import com.skybook.booking.domain.model.BookingStatus;

/** JPA implementation of the {@link BookingRepository} port. */
@Repository
class JpaBookingRepository implements BookingRepository {

    private final BookingJpaRepository jpa;

    JpaBookingRepository(BookingJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public Booking save(Booking booking) {
        BookingState state = booking.state();
        try {
            BookingEntity entity;
            if (state.id() == null) {
                entity = jpa.saveAndFlush(BookingPersistenceMapper.newEntity(state));
            } else {
                entity = jpa.findById(state.id())
                        .orElseThrow(() -> new ObjectOptimisticLockingFailureException(BookingEntity.class,
                                state.id()));
                // The aggregate was loaded at version N; if the row moved on, someone else changed it meanwhile.
                if (entity.getVersion() != state.version()) {
                    throw new ObjectOptimisticLockingFailureException(BookingEntity.class, state.id());
                }
                BookingPersistenceMapper.apply(state, entity);
                jpa.flush();
            }
            return BookingPersistenceMapper.toDomain(entity);
        } catch (DataIntegrityViolationException ex) {
            String message = String.valueOf(ex.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("uq_booking_customer_idempotency")) {
                throw new DuplicateBookingException(true, ex);
            }
            if (message.contains("uq_booking_reference")) {
                throw new DuplicateBookingException(false, ex);
            }
            throw ex;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Booking> findByReference(String reference) {
        return jpa.findByReference(reference).map(BookingPersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Booking> findByCustomerAndIdempotencyKey(String customerId, String idempotencyKey) {
        return jpa.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey)
                .map(BookingPersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Booking> findByCustomer(String customerId, int page, int size) {
        return toPageResult(jpa.findByCustomerIdOrderByCreatedAtDesc(customerId, PageRequest.of(page, size)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Booking> search(BookingStatus status, Long flightId, int page, int size) {
        return toPageResult(jpa.search(status == null ? null : status.name(), flightId, PageRequest.of(page, size)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findWithHoldExpiredBefore(BookingStatus status, Instant now, int limit) {
        return jpa.findReferencesWithHoldExpiredBefore(status.name(), now, Limit.of(limit));
    }

    private static PageResult<Booking> toPageResult(Page<BookingEntity> page) {
        return new PageResult<>(page.getContent().stream().map(BookingPersistenceMapper::toDomain).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
