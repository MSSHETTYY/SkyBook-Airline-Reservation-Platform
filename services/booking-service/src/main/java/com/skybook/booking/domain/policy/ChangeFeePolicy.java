package com.skybook.booking.domain.policy;

import java.math.BigDecimal;
import java.util.Currency;

import com.skybook.booking.domain.model.CustomerTier;
import com.skybook.common.money.Money;

/**
 * Date-change fee: a flat fee per passenger on top of the fare difference, waived for GOLD and PLATINUM
 * loyalty members. When the CRM is unreachable the tier falls back to STANDARD (the fee applies), so an
 * outage can never give away fee waivers.
 */
public final class ChangeFeePolicy {

    static final BigDecimal FEE_PER_PASSENGER = new BigDecimal("50.00");

    public Money feeFor(int passengers, CustomerTier tier, Currency currency) {
        if (passengers <= 0) {
            throw new IllegalArgumentException("At least one passenger required");
        }
        if (tier.waivesChangeFees()) {
            return Money.zero(currency);
        }
        return new Money(FEE_PER_PASSENGER, currency).times(passengers);
    }
}
