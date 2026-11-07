package com.skybook.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An immutable monetary amount in a specific currency.
 *
 * <p>Amounts are {@link BigDecimal}s normalised to the currency's minor-unit scale (2 for USD/EUR, 0 for
 * JPY), so {@code 10} and {@code 10.00} are equal. Arithmetic across currencies is rejected rather
 * than silently converted. Negative amounts are allowed because fare differences can be negative.
 *
 * <p>Framework-free (enforced by ArchUnit): it is a generic value type, not a shared domain model.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    /** Rounding used when a calculation produces more fraction digits than the currency allows. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        amount = amount.setScale(currency.getDefaultFractionDigits(), ROUNDING);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public static Money of(String amount, String currencyCode) {
        return of(new BigDecimal(amount), currencyCode);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }

    /** Amount in minor units (cents), as payment providers such as Stripe expect it. */
    public long minorUnits() {
        return amount.movePointRight(currency.getDefaultFractionDigits()).longValueExact();
    }

    public static Money ofMinorUnits(long minorUnits, String currencyCode) {
        Currency currency = Currency.getInstance(currencyCode);
        return new Money(BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits()), currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor), currency);
    }

    public Money times(int quantity) {
        return times(BigDecimal.valueOf(quantity));
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: %s vs %s".formatted(currency, other.currency));
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
