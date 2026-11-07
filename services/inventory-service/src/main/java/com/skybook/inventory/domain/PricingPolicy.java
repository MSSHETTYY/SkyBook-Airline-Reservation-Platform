package com.skybook.inventory.domain;

import java.math.BigDecimal;

import com.skybook.common.money.Money;

/**
 * Seat pricing: base fare x cabin multiplier x demand surcharge.
 *
 * <p>Demand is the flight's load factor (booked or validly held seats / capacity) at quote time.
 * The quoted price is stored on the hold, so later price changes never affect a customer who is
 * already paying.
 */
public final class PricingPolicy {

    static final BigDecimal BUSINESS_MULTIPLIER = new BigDecimal("2.5");
    static final BigDecimal HIGH_DEMAND_SURCHARGE = new BigDecimal("1.20");
    static final BigDecimal MEDIUM_DEMAND_SURCHARGE = new BigDecimal("1.10");
    static final double HIGH_DEMAND_LOAD_FACTOR = 0.8;
    static final double MEDIUM_DEMAND_LOAD_FACTOR = 0.5;

    public Money priceFor(CabinClass cabin, Money baseFare, int occupiedSeats, int capacity) {
        if (capacity <= 0 || occupiedSeats < 0 || occupiedSeats > capacity) {
            throw new IllegalArgumentException("Invalid occupancy %d/%d".formatted(occupiedSeats, capacity));
        }
        BigDecimal cabinMultiplier = cabin == CabinClass.BUSINESS ? BUSINESS_MULTIPLIER : BigDecimal.ONE;
        return baseFare.times(cabinMultiplier.multiply(surcharge((double) occupiedSeats / capacity)));
    }

    private static BigDecimal surcharge(double loadFactor) {
        if (loadFactor >= HIGH_DEMAND_LOAD_FACTOR) {
            return HIGH_DEMAND_SURCHARGE;
        }
        if (loadFactor >= MEDIUM_DEMAND_LOAD_FACTOR) {
            return MEDIUM_DEMAND_SURCHARGE;
        }
        return BigDecimal.ONE;
    }
}
