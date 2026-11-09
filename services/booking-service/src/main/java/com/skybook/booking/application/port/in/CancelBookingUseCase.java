package com.skybook.booking.application.port.in;

import com.skybook.booking.domain.model.Booking;
import com.skybook.booking.domain.policy.RefundPolicy;

/** Driving port: voluntary cancellation of a confirmed booking. */
public interface CancelBookingUseCase {

    RefundPolicy.Quote quoteCancellation(String reference, String callerId, boolean callerIsStaff);

    Booking cancel(String reference, String callerId, boolean callerIsStaff);
}
