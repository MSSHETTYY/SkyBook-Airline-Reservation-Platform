package com.skybook.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;

class SeatHoldTest {

    private static final Instant NOW = Instant.parse("2026-12-01T08:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(15);

    private Flight flight;
    private Seat seat1A;
    private Seat seat1B;

    @BeforeEach
    void setUp() {
        flight = new Flight("SB101", "DXB", "LHR", NOW.plus(Duration.ofDays(10)),
                NOW.plus(Duration.ofDays(10)).plus(Duration.ofHours(7)), "A320", Money.of("100.00", "USD"), "key-f",
                NOW);
        ReflectionTestUtils.setField(flight, "id", 1L);
        seat1A = new Seat(1L, "1A", CabinClass.BUSINESS);
        seat1B = new Seat(1L, "1B", CabinClass.ECONOMY);
    }

    private SeatHold place(Seat... seats) {
        return SeatHold.place(flight, List.of(seats), seat -> Money.of("150.00", "USD"), NOW, TTL,
                "key-" + seats.length, "fp");
    }

    @Test
    void placingAHoldTakesSeatsAndLocksPrices() {
        SeatHold hold = place(seat1B, seat1A);

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.getExpiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(hold.seatNumbers()).containsExactly("1A", "1B");
        assertThat(hold.total()).isEqualTo(Money.of("300.00", "USD"));
        assertThat(seat1A.isHeldBy(hold.getId())).isTrue();
        assertThat(seat1A.isAvailableAt(NOW)).isFalse();
        assertThat(hold.matchesRequest("fp")).isTrue();
    }

    @Test
    void cannotHoldSeatsThatAreTaken() {
        place(seat1A);

        assertThatThrownBy(() -> place(seat1A, seat1B))
                .isInstanceOfSatisfying(SkyBookException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(InventoryErrorCode.SEAT_UNAVAILABLE);
                    assertThat(ex.getProperties()).containsEntry("seatNumbers", List.of("1A"));
                });
        assertThat(seat1B.isAvailableAt(NOW)).as("no partial hold").isTrue();
    }

    @Test
    void expiredHoldDoesNotBlockNewHoldsEvenBeforeTheSweeperRuns() {
        place(seat1A);
        Instant later = NOW.plus(TTL);

        assertThat(seat1A.isAvailableAt(later)).isTrue();
        SeatHold second = SeatHold.place(flight, List.of(seat1A), seat -> Money.of("1", "USD"), later, TTL, "k2", "fp");
        assertThat(seat1A.isHeldBy(second.getId())).isTrue();
    }

    @Test
    void confirmBooksSeatsAndIsIdempotent() {
        SeatHold hold = place(seat1A, seat1B);

        assertThat(hold.confirm("K7QX2M", List.of(seat1A, seat1B), NOW)).isTrue();
        assertThat(hold.confirm("K7QX2M", List.of(seat1A, seat1B), NOW)).isFalse();

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.CONFIRMED);
        assertThat(seat1A.getStatus()).isEqualTo(SeatStatus.BOOKED);
        assertThat(seat1A.getBookingReference()).isEqualTo("K7QX2M");
        assertThatThrownBy(() -> hold.confirm("OTHER2", List.of(seat1A), NOW))
                .hasFieldOrPropertyWithValue("errorCode", InventoryErrorCode.HOLD_NOT_ACTIVE);
    }

    @Test
    void lateConfirmationSucceedsWhenSeatsWereNotTaken() {
        SeatHold hold = place(seat1A);
        Instant late = NOW.plus(TTL).plusSeconds(60);
        hold.expireIfDue(List.of(seat1A), late);

        assertThat(hold.confirm("K7QX2M", List.of(seat1A), late)).isTrue();
        assertThat(seat1A.getStatus()).isEqualTo(SeatStatus.BOOKED);
    }

    @Test
    void lateConfirmationFailsWhenSeatsWereTaken() {
        SeatHold hold = place(seat1A);
        Instant late = NOW.plus(TTL).plusSeconds(60);
        SeatHold.place(flight, List.of(seat1A), seat -> Money.of("1", "USD"), late, TTL, "k2", "fp");

        assertThatThrownBy(() -> hold.confirm("K7QX2M", List.of(seat1A), late))
                .isInstanceOfSatisfying(SkyBookException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(InventoryErrorCode.HOLD_EXPIRED));
    }

    @Test
    void releaseReturnsSeatsAndIsIdempotent() {
        SeatHold hold = place(seat1A);

        assertThat(hold.release(List.of(seat1A))).isTrue();
        assertThat(hold.release(List.of(seat1A))).isFalse();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
        assertThat(seat1A.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThatThrownBy(() -> hold.confirm("K7QX2M", List.of(seat1A), NOW))
                .hasFieldOrPropertyWithValue("errorCode", InventoryErrorCode.HOLD_NOT_ACTIVE);
    }

    @Test
    void confirmedHoldCannotBeReleased() {
        SeatHold hold = place(seat1A);
        hold.confirm("K7QX2M", List.of(seat1A), NOW);

        assertThatThrownBy(() -> hold.release(List.of(seat1A)))
                .hasFieldOrPropertyWithValue("errorCode", InventoryErrorCode.HOLD_NOT_ACTIVE);
        assertThat(seat1A.releaseBooking("WRONG2")).isFalse();
        assertThat(seat1A.releaseBooking("K7QX2M")).isTrue();
        assertThat(seat1A.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void expiryOnlyAppliesToActiveHoldsPastTheirDeadline() {
        SeatHold hold = place(seat1A);

        assertThat(hold.expireIfDue(List.of(seat1A), NOW.plus(TTL).minusSeconds(1))).isFalse();
        assertThat(hold.expireIfDue(List.of(seat1A), NOW.plus(TTL))).isTrue();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        assertThat(seat1A.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(hold.expireIfDue(List.of(seat1A), NOW.plus(TTL))).isFalse();
    }

    @Test
    void expiryDoesNotReleaseSeatsAlreadyTakenByAnotherHold() {
        SeatHold first = place(seat1A);
        Instant later = NOW.plus(TTL).plusSeconds(1);
        SeatHold second = SeatHold.place(flight, List.of(seat1A), seat -> Money.of("1", "USD"), later, TTL, "k2", "fp");

        first.expireIfDue(List.of(seat1A), later);

        assertThat(seat1A.isHeldBy(second.getId())).isTrue();
    }

    @Test
    void refusesHoldsOnCancelledOrDepartingFlights() {
        flight.cancel();
        assertThatThrownBy(() -> place(seat1A))
                .hasFieldOrPropertyWithValue("errorCode", InventoryErrorCode.FLIGHT_NOT_BOOKABLE);

        Flight soon = new Flight("SB102", "DXB", "LHR", NOW.plus(Duration.ofMinutes(30)), NOW.plus(Duration.ofHours(8)),
                "A320", Money.of("100.00", "USD"), "k", NOW);
        assertThatThrownBy(() -> soon.requireBookable(NOW))
                .hasFieldOrPropertyWithValue("errorCode", InventoryErrorCode.FLIGHT_NOT_BOOKABLE);
    }

    @Test
    void flightValidatesItsData() {
        Instant dep = NOW.plus(Duration.ofDays(1));
        assertThatThrownBy(() -> new Flight("SB1", "DXB", "DXB", dep, dep.plusSeconds(60), "A320",
                Money.of("1", "USD"), "k", NOW))
                .hasFieldOrPropertyWithValue("errorCode", InventoryErrorCode.INVALID_FLIGHT);
        assertThatThrownBy(() -> new Flight("SB1", "DXB", "LHR", dep, dep, "A320",
                Money.of("1", "USD"), "k", NOW))
                .hasFieldOrPropertyWithValue("errorCode", InventoryErrorCode.INVALID_FLIGHT);
        assertThatThrownBy(() -> new Flight("SB1", "DXB", "LHR", dep, dep.plusSeconds(60), "A320",
                Money.of("0", "USD"), "k", NOW))
                .hasFieldOrPropertyWithValue("errorCode", InventoryErrorCode.INVALID_FLIGHT);
    }
}
