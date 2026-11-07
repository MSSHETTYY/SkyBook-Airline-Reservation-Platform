package com.skybook.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.skybook.common.money.Money;

class PricingAndLayoutTest {

    private final PricingPolicy pricing = new PricingPolicy();
    private final Money base = Money.of("100.00", "USD");

    @ParameterizedTest(name = "{0} at {1}/100 occupied -> {2}")
    @CsvSource({
            "ECONOMY, 0, 100.00",
            "ECONOMY, 49, 100.00",
            "ECONOMY, 50, 110.00",
            "ECONOMY, 80, 120.00",
            "BUSINESS, 0, 250.00",
            "BUSINESS, 50, 275.00",
            "BUSINESS, 100, 300.00"
    })
    void pricesByCabinAndDemand(CabinClass cabin, int occupied, String expected) {
        assertThat(pricing.priceFor(cabin, base, occupied, 100)).isEqualTo(Money.of(expected, "USD"));
    }

    @Test
    void rejectsImpossibleOccupancy() {
        assertThatIllegalArgumentException().isThrownBy(() -> pricing.priceFor(CabinClass.ECONOMY, base, 5, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> pricing.priceFor(CabinClass.ECONOMY, base, 11, 10));
    }

    @Test
    void generatesBusinessRowsFirst() {
        List<Seat> seats = SeatLayoutGenerator.generate(7L, 3, "ABCD", 1);

        assertThat(seats).hasSize(12);
        assertThat(seats).extracting(Seat::getSeatNumber).startsWith("1A", "1B", "1C", "1D", "2A").endsWith("3D");
        assertThat(seats).filteredOn(s -> s.getCabinClass() == CabinClass.BUSINESS).hasSize(4);
        assertThat(seats).allMatch(s -> s.getFlightId().equals(7L) && s.getStatus() == SeatStatus.AVAILABLE);
    }

    @Test
    void rejectsInvalidLayouts() {
        assertThatThrownBy(() -> SeatLayoutGenerator.generate(1L, 0, "AB", 0))
                .hasFieldOrPropertyWithValue("errorCode", InventoryErrorCode.INVALID_FLIGHT);
        assertThatThrownBy(() -> SeatLayoutGenerator.generate(1L, 2, "AA", 0))
                .hasFieldOrPropertyWithValue("errorCode", InventoryErrorCode.INVALID_FLIGHT);
        assertThatThrownBy(() -> SeatLayoutGenerator.generate(1L, 2, "AB", 3))
                .hasFieldOrPropertyWithValue("errorCode", InventoryErrorCode.INVALID_FLIGHT);
    }
}
