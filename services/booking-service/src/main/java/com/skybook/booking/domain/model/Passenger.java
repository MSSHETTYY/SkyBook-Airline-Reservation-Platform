package com.skybook.booking.domain.model;

import java.time.LocalDate;
import java.util.Objects;

import com.skybook.common.money.Money;

/**
 * A traveller on a booking with their seat, the price paid for it, and (once ticketed) the ticket number.
 * Name and date of birth are personal data: they are encrypted at rest and never logged.
 */
public record Passenger(String firstName, String lastName, LocalDate dateOfBirth, String seatNumber, Money seatPrice,
                        String ticketNumber) {

    public Passenger {
        requireText(firstName, "firstName");
        requireText(lastName, "lastName");
        Objects.requireNonNull(dateOfBirth, "dateOfBirth");
        requireText(seatNumber, "seatNumber");
        Objects.requireNonNull(seatPrice, "seatPrice");
    }

    public Passenger withTicket(String number) {
        return new Passenger(firstName, lastName, dateOfBirth, seatNumber, seatPrice, number);
    }

    public Passenger withSeat(String newSeatNumber, Money newPrice) {
        return new Passenger(firstName, lastName, dateOfBirth, newSeatNumber, newPrice, null);
    }

    /** Never print personal data, even by accident in a log statement. */
    @Override
    public String toString() {
        return "Passenger[seat=" + seatNumber + "]";
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
