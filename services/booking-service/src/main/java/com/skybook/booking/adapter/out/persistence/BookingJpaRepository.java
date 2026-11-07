package com.skybook.booking.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BookingJpaRepository extends JpaRepository<BookingEntity, Long> {

    Optional<BookingEntity> findByReference(String reference);

    Optional<BookingEntity> findByCustomerIdAndIdempotencyKey(String customerId, String idempotencyKey);

    /** Served by ix_booking_customer_created. */
    Page<BookingEntity> findByCustomerIdOrderByCreatedAtDesc(String customerId, Pageable pageable);

    @Query("""
            select b from BookingEntity b
            where (:status is null or b.status = :status) and (:flightId is null or b.flightId = :flightId)
            order by b.createdAt desc
            """)
    Page<BookingEntity> search(@Param("status") String status, @Param("flightId") Long flightId, Pageable pageable);

    /** Served by ix_booking_status_hold_expiry. */
    @Query("""
            select b.reference from BookingEntity b
            where b.status = :status and b.holdExpiresAt <= :now
            order by b.holdExpiresAt
            """)
    List<String> findReferencesWithHoldExpiredBefore(@Param("status") String status, @Param("now") Instant now,
                                                     Limit limit);
}
