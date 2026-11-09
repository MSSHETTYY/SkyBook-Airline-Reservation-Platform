package com.skybook.booking.domain.policy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import com.skybook.booking.domain.model.Booking;
import com.skybook.common.money.Money;

/**
 * Fare rules for voluntary cancellation, relative to departure:
 * <ul>
 *   <li>7 days or more: full refund of what is still refundable;</li>
 *   <li>24 hours to 7 days: 50 percent;</li>
 *   <li>less than 24 hours: no refund.</li>
 * </ul>
 */
public final class RefundPolicy {

    static final Duration FULL_REFUND_NOTICE = Duration.ofDays(7);
    static final Duration HALF_REFUND_NOTICE = Duration.ofHours(24);

    /** Refund amount, percentage and the human-readable rule that applies. */
    public record Quote(Money amount, int percentage, String rule) {
    }

    public Quote quote(Booking booking, Instant now) {
        Duration notice = Duration.between(now, booking.getFlight().departureTime());
        Money refundable = booking.refundableAmount();
        if (notice.compareTo(FULL_REFUND_NOTICE) >= 0) {
            return new Quote(refundable, 100, "At least 7 days before departure: full refund");
        }
        if (notice.compareTo(HALF_REFUND_NOTICE) >= 0) {
            return new Quote(refundable.times(new BigDecimal("0.50")), 50,
                    "Between 24 hours and 7 days before departure: 50% refund");
        }
        return new Quote(Money.zero(refundable.currency()), 0, "Less than 24 hours before departure: no refund");
    }
}
