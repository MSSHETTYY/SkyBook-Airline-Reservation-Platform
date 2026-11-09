package com.skybook.booking.domain.model;

/** Loyalty tier from the CRM; higher tiers get fee waivers. */
public enum CustomerTier {
    STANDARD, SILVER, GOLD, PLATINUM;

    public boolean waivesChangeFees() {
        return this == GOLD || this == PLATINUM;
    }
}
