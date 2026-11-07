package com.skybook.booking.application.port.in;

import java.time.LocalDate;
import java.util.List;

import com.skybook.booking.domain.model.Booking;

/** Driving port: create a booking, holding the requested seats for 15 minutes. */
public interface CreateBookingUseCase {

    Booking create(CreateBookingCommand command);

    /** Input of {@link #create}. The idempotency key is scoped to the customer. */
    record CreateBookingCommand(String customerId, String idempotencyKey, Long flightId, String contactEmail,
                                List<PassengerDetails> passengers) {
    }

    /** One traveller and the seat they want. */
    record PassengerDetails(String firstName, String lastName, LocalDate dateOfBirth, String seatNumber) {

        /** Never print personal data. */
        @Override
        public String toString() {
            return "PassengerDetails[seat=" + seatNumber + "]";
        }
    }
}
