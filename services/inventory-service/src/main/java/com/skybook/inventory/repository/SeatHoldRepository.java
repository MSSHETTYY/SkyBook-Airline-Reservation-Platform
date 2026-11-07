package com.skybook.inventory.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.skybook.inventory.domain.HoldStatus;
import com.skybook.inventory.domain.SeatHold;

public interface SeatHoldRepository extends JpaRepository<SeatHold, String> {

    Optional<SeatHold> findByIdempotencyKey(String idempotencyKey);

    /** Served by index ix_hold_status_expiry; bounded so one sweep never loads an unbounded batch. */
    @Query("select h.id from SeatHold h where h.status = :status and h.expiresAt <= :now order by h.expiresAt")
    List<String> findDueIds(@Param("status") HoldStatus status, @Param("now") Instant now, Limit limit);
}
