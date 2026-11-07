package com.skybook.inventory.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.skybook.inventory.domain.Seat;
import com.skybook.inventory.domain.SeatStatus;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByFlightIdOrderById(Long flightId);

    List<Seat> findByFlightIdAndSeatNumberIn(Long flightId, Collection<String> seatNumbers);

    List<Seat> findByFlightIdAndBookingReferenceAndStatus(Long flightId, String bookingReference, SeatStatus status);
}
