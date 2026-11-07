package com.skybook.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void normalisesScaleToCurrency() {
        assertThat(Money.of("10", "USD")).isEqualTo(Money.of("10.00", "USD"));
        assertThat(Money.of("10.005", "USD").amount()).isEqualByComparingTo("10.01");
        assertThat(Money.of("1500.4", "JPY").amount()).isEqualByComparingTo("1500");
    }

    @Test
    void arithmetic() {
        Money a = Money.of("100.10", "USD");
        Money b = Money.of("0.90", "USD");

        assertThat(a.plus(b)).isEqualTo(Money.of("101.00", "USD"));
        assertThat(b.minus(a)).isEqualTo(Money.of("-99.20", "USD"));
        assertThat(a.times(3)).isEqualTo(Money.of("300.30", "USD"));
        assertThat(a.times(new BigDecimal("1.1"))).isEqualTo(Money.of("110.11", "USD"));
        assertThat(a.negate().isNegative()).isTrue();
        assertThat(Money.zero(a.currency()).isZero()).isTrue();
        assertThat(a.isPositive()).isTrue();
        assertThat(a.isGreaterThan(b)).isTrue();
    }

    @Test
    void minorUnitsRoundTrip() {
        Money money = Money.of("249.50", "USD");

        assertThat(money.minorUnits()).isEqualTo(24950L);
        assertThat(Money.ofMinorUnits(24950L, "USD")).isEqualTo(money);
        assertThat(Money.ofMinorUnits(1500L, "JPY").amount()).isEqualByComparingTo("1500");
    }

    @Test
    void rejectsMixedCurrencies() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Money.of("1", "USD").plus(Money.of("1", "EUR")))
                .withMessageContaining("Currency mismatch");
    }

    @Test
    void readableToString() {
        assertThat(Money.of("5", "USD")).hasToString("5.00 USD");
        assertThat(Money.of("5", "USD").currencyCode()).isEqualTo("USD");
    }
}
