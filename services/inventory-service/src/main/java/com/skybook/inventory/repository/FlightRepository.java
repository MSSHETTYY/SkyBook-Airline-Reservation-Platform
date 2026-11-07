package com.skybook.inventory.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.skybook.inventory.domain.Flight;
import com.skybook.inventory.domain.FlightStatus;

public interface FlightRepository extends JpaRepository<Flight, Long> {

    /** Served by index ix_flight_route_departure. */
    @Query("""
            select f from Flight f
            where f.origin = :origin and f.destination = :destination and f.status = :status
              and f.departureTime >= :fromInclusive and f.departureTime < :toExclusive
            order by f.departureTime
            """)
    List<Flight> searchRoute(@Param("origin") String origin, @Param("destination") String destination,
                             @Param("status") FlightStatus status, @Param("fromInclusive") Instant fromInclusive,
                             @Param("toExclusive") Instant toExclusive);

    Optional<Flight> findByCreationKey(String creationKey);

    boolean existsByFlightNumberAndDepartureTime(String flightNumber, Instant departureTime);

    Page<Flight> findAllByOrderByDepartureTimeDesc(Pageable pageable);
}
