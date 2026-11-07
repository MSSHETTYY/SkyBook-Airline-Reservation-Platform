package com.skybook.booking.application.port.in;

import com.skybook.booking.domain.model.Booking;
import com.skybook.booking.domain.model.BookingStatus;

/** Driving port: read bookings. Access rules (owner vs staff) are enforced by the implementation. */
public interface BookingQueries {

    /**
     * Returns the booking if the caller may see it. Customers only see their own bookings; for anyone
     * else the booking is reported as not found, so references cannot be probed.
     */
    Booking get(String reference, String callerId, boolean callerIsStaff);

    PageResult<Booking> listForCustomer(String customerId, int page, int size);

    PageResult<Booking> search(BookingStatus status, Long flightId, int page, int size);
}
