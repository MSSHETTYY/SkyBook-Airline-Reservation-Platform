package com.skybook.inventory.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.skybook.inventory.config.InventoryProperties;
import com.skybook.inventory.domain.HoldStatus;
import com.skybook.inventory.domain.SeatHold;
import com.skybook.inventory.repository.SeatHoldRepository;
import com.skybook.inventory.repository.SeatRepository;

/**
 * Returns the seats of expired holds to sale.
 *
 * <p>Safe to run on every replica at once without a distributed lock: each hold is expired in its own
 * transaction under optimistic locking, so if two replicas (or a confirmation) touch the same hold,
 * exactly one wins and the others skip it. Correctness does not depend on this job running promptly:
 * expired holds already count as available when new holds are placed; the job only tidies state and
 * refreshes the read model.
 */
@Component
public class HoldExpiryJob {

    private static final Logger LOG = LoggerFactory.getLogger(HoldExpiryJob.class);

    private final SeatHoldRepository holds;
    private final SeatRepository seats;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final InventoryProperties properties;
    private final Clock clock;

    public HoldExpiryJob(SeatHoldRepository holds, SeatRepository seats, TransactionTemplate tx,
                         ApplicationEventPublisher events, InventoryProperties properties, Clock clock) {
        this.holds = holds;
        this.seats = seats;
        this.tx = tx;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${skybook.inventory.hold-expiry-interval:PT30S}", initialDelayString = "PT10S")
    public void run() {
        int expired = expireDueHolds(clock.instant());
        if (expired > 0) {
            LOG.info("Expired {} seat hold(s)", expired);
        }
    }

    /** Expires up to one batch of due holds; returns how many were expired. */
    public int expireDueHolds(Instant now) {
        List<String> due = holds.findDueIds(HoldStatus.ACTIVE, now, Limit.of(properties.expiryBatchSize()));
        int expired = 0;
        for (String holdId : due) {
            try {
                Boolean changed = tx.execute(status -> expire(holdId, now));
                if (Boolean.TRUE.equals(changed)) {
                    expired++;
                }
            } catch (ConcurrencyFailureException ex) {
                LOG.debug("Hold {} changed concurrently while expiring; skipping", holdId);
            }
        }
        return expired;
    }

    private boolean expire(String holdId, Instant now) {
        SeatHold hold = holds.findById(holdId).orElse(null);
        if (hold == null) {
            return false;
        }
        boolean changed = hold.expireIfDue(seats.findByFlightIdAndSeatNumberIn(hold.getFlightId(), hold.seatNumbers()),
                now);
        if (changed) {
            holds.flush();
            events.publishEvent(new SeatInventoryChanged(hold.getFlightId()));
        }
        return changed;
    }
}
