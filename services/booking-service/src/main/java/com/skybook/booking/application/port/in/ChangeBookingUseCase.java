package com.skybook.booking.application.port.in;

import java.util.List;

import com.skybook.booking.domain.model.Booking;

/** Driving port: move a confirmed booking to another flight. */
public interface ChangeBookingUseCase {

    Booking changeDate(ChangeDateCommand command);

    /** Input of {@link #changeDate}; one seat per passenger, in passenger order. */
    record ChangeDateCommand(String reference, String callerId, boolean callerIsStaff, Long newFlightId,
                             List<String> seatNumbers, String idempotencyKey) {
    }
}
