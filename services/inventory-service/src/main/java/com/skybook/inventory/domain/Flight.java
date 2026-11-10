package com.skybook.inventory.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;

/** A scheduled flight. Seats are separate rows so each can be locked independently. */
@Entity
@Table(name = "flight")
public class Flight {

    /** Holds are refused this close to departure: check-in and ticketing need time. */
    public static final Duration BOOKING_CUTOFF = Duration.ofHours(1);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flight_number", nullable = false)
    private String flightNumber;

    // Fixed-length CHAR(3) in the schema; Hibernate 7 does not treat CHAR as equivalent to VARCHAR.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String origin;

    // Fixed-length CHAR(3) in the schema; Hibernate 7 does not treat CHAR as equivalent to VARCHAR.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String destination;

    @Column(name = "departure_time", nullable = false)
    private Instant departureTime;

    @Column(name = "arrival_time", nullable = false)
    private Instant arrivalTime;

    @Column(name = "aircraft_type", nullable = false)
    private String aircraftType;

    @Column(name = "base_fare_amount", nullable = false)
    private BigDecimal baseFareAmount;

    // Fixed-length CHAR(3) in the schema; Hibernate 7 does not treat CHAR as equivalent to VARCHAR.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false)
    private FlightStatus status;

    @Column(name = "creation_key")
    private String creationKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected Flight() {
        // for JPA
    }

    public Flight(String flightNumber, String origin, String destination, Instant departureTime, Instant arrivalTime,
                  String aircraftType, Money baseFare, String creationKey, Instant createdAt) {
        if (Objects.equals(origin, destination)) {
            throw new SkyBookException(InventoryErrorCode.INVALID_FLIGHT, "Origin and destination must differ");
        }
        if (!arrivalTime.isAfter(departureTime)) {
            throw new SkyBookException(InventoryErrorCode.INVALID_FLIGHT, "Arrival must be after departure");
        }
        if (!baseFare.isPositive()) {
            throw new SkyBookException(InventoryErrorCode.INVALID_FLIGHT, "Base fare must be positive");
        }
        this.flightNumber = flightNumber;
        this.origin = origin;
        this.destination = destination;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.aircraftType = aircraftType;
        this.baseFareAmount = baseFare.amount();
        this.currency = baseFare.currencyCode();
        this.status = FlightStatus.SCHEDULED;
        this.creationKey = creationKey;
        this.createdAt = createdAt;
    }

    /** Throws unless new holds may be placed on this flight at {@code now}. */
    public void requireBookable(Instant now) {
        if (status != FlightStatus.SCHEDULED) {
            throw new SkyBookException(InventoryErrorCode.FLIGHT_NOT_BOOKABLE, "Flight %s is %s"
                    .formatted(flightNumber, status.name().toLowerCase(Locale.ROOT)));
        }
        if (!now.plus(BOOKING_CUTOFF).isBefore(departureTime)) {
            throw new SkyBookException(InventoryErrorCode.FLIGHT_NOT_BOOKABLE,
                    "Flight %s closes for booking %d minutes before departure"
                            .formatted(flightNumber, BOOKING_CUTOFF.toMinutes()));
        }
    }

    public void cancel() {
        this.status = FlightStatus.CANCELLED;
    }

    public Money baseFare() {
        return Money.of(baseFareAmount, currency);
    }

    public Long getId() {
        return id;
    }

    public String getFlightNumber() {
        return flightNumber;
    }

    public String getOrigin() {
        return origin;
    }

    public String getDestination() {
        return destination;
    }

    public Instant getDepartureTime() {
        return departureTime;
    }

    public Instant getArrivalTime() {
        return arrivalTime;
    }

    public String getAircraftType() {
        return aircraftType;
    }

    public FlightStatus getStatus() {
        return status;
    }

    public String getCreationKey() {
        return creationKey;
    }
}
