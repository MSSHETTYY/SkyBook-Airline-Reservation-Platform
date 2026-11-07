package com.skybook.booking.adapter.in.scheduling;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.skybook.booking.application.port.in.ExpireUnpaidBookingsUseCase;

/**
 * Driving adapter: periodically expires bookings that were not paid before their seat hold ran out.
 * Safe on multiple replicas: each booking is expired under optimistic locking, so only one replica wins.
 */
@Component
class UnpaidBookingExpiryJob {

    private static final Logger LOG = LoggerFactory.getLogger(UnpaidBookingExpiryJob.class);

    private final ExpireUnpaidBookingsUseCase useCase;
    private final Clock clock;

    UnpaidBookingExpiryJob(ExpireUnpaidBookingsUseCase useCase, Clock clock) {
        this.useCase = useCase;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${skybook.booking.expiry-interval:PT30S}", initialDelayString = "PT15S")
    void run() {
        int expired = useCase.expireOverdue(clock.instant());
        if (expired > 0) {
            LOG.info("Expired {} unpaid booking(s)", expired);
        }
    }
}
