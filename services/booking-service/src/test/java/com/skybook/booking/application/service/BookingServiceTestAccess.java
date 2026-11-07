package com.skybook.booking.application.service;

import com.skybook.booking.application.port.in.CreateBookingUseCase.CreateBookingCommand;

/** Exposes package-private helpers of {@link BookingService} to tests in other packages. */
public final class BookingServiceTestAccess {

    private BookingServiceTestAccess() {
    }

    public static String inventoryKey(CreateBookingCommand command) {
        return BookingService.inventoryKey(command);
    }

    public static String fingerprint(CreateBookingCommand command) {
        return BookingService.fingerprint(command);
    }
}
