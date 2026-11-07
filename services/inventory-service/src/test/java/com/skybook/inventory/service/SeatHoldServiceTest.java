package com.skybook.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.error.ResourceNotFoundException;
import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;
import com.skybook.inventory.domain.HoldStatus;
import com.skybook.inventory.domain.InventoryErrorCode;
import com.skybook.inventory.domain.SeatStatus;
import com.skybook.inventory.repository.SeatRepository;
import com.skybook.inventory.service.FlightAdminService.CreateFlightCommand;
import com.skybook.inventory.support.InventoryTestSupport;

/** Service behaviour against the real schema (Flyway migrations on H2 in Oracle mode). */
class SeatHoldServiceTest extends InventoryTestSupport {

    @Autowired
    private SeatHoldService holds;

    @Autowired
    private SeatMapService seatMaps;

    @Autowired
    private FlightQueryService queries;

    @Autowired
    private HoldExpiryJob expiryJob;

    @Autowired
    private SeatRepository seats;

    @Test
    void holdsSeatsAndLocksInPrices() {
        Long flightId = newFlight(10, "ABCD");

        SeatHoldView hold = holds.holdSeats(flightId, List.of("2B", "1A"), "hold-key-0001");

        assertThat(hold.status()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.seats()).extracting(SeatHoldView.Line::seatNumber).containsExactly("1A", "2B");
        assertThat(hold.totalPrice()).isEqualTo(Money.of("350.00", "USD"));
        assertThat(hold.expiresAt()).isEqualTo(START.plus(Duration.ofMinutes(15)));
        assertThat(seatMaps.seatMap(flightId).seats())
                .filteredOn(seat -> List.of("1A", "2B").contains(seat.seatNumber()))
                .noneMatch(SeatMapSnapshot.SeatView::available);
    }

    @Test
    void sameIdempotencyKeyReplaysTheOriginalHold() {
        Long flightId = newFlight(5, "AB");
        SeatHoldView first = holds.holdSeats(flightId, List.of("3A"), "hold-key-0002");

        SeatHoldView retry = holds.holdSeats(flightId, List.of("3A"), "hold-key-0002");

        assertThat(retry.holdId()).isEqualTo(first.holdId());
    }

    @Test
    void reusingAnIdempotencyKeyForDifferentSeatsIsRejected() {
        Long flightId = newFlight(5, "AB");
        holds.holdSeats(flightId, List.of("3A"), "hold-key-0003");

        assertThatThrownBy(() -> holds.holdSeats(flightId, List.of("3B"), "hold-key-0003"))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void secondHoldOnTheSameSeatFails() {
        Long flightId = newFlight(5, "AB");
        holds.holdSeats(flightId, List.of("4A"), "hold-key-0004");

        assertThatThrownBy(() -> holds.holdSeats(flightId, List.of("4A", "4B"), "hold-key-0005"))
                .isInstanceOfSatisfying(SkyBookException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(InventoryErrorCode.SEAT_UNAVAILABLE);
                    assertThat(ex.getProperties()).containsEntry("seatNumbers", List.of("4A"));
                });
        assertThat(seats.findByFlightIdAndSeatNumberIn(flightId, List.of("4B")))
                .allMatch(seat -> seat.getStatus() == SeatStatus.AVAILABLE);
    }

    @Test
    void unknownSeatsAndFlightsAreRejected() {
        Long flightId = newFlight(2, "AB");

        assertThatThrownBy(() -> holds.holdSeats(flightId, List.of("9Z"), "hold-key-0006"))
                .hasFieldOrPropertyWithValue("errorCode", InventoryErrorCode.UNKNOWN_SEAT);
        assertThatThrownBy(() -> holds.holdSeats(999_999L, List.of("1A"), "hold-key-0007"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> holds.hold("00000000-0000-0000-0000-000000000000"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void confirmBooksSeatsAndBookedSeatsCanBeReleasedLater() {
        Long flightId = newFlight(5, "AB");
        SeatHoldView hold = holds.holdSeats(flightId, List.of("2A", "2B"), "hold-key-0008");

        SeatHoldView confirmed = holds.confirm(hold.holdId(), "K7QX2M");
        holds.confirm(hold.holdId(), "K7QX2M");

        assertThat(confirmed.status()).isEqualTo(HoldStatus.CONFIRMED);
        assertThat(confirmed.bookingReference()).isEqualTo("K7QX2M");
        assertThat(holds.hold(hold.holdId()).status()).isEqualTo(HoldStatus.CONFIRMED);

        assertThat(holds.releaseBookedSeats(flightId, "K7QX2M")).containsExactly("2A", "2B");
        assertThat(holds.releaseBookedSeats(flightId, "K7QX2M")).isEmpty();
        assertThat(seats.findByFlightIdAndSeatNumberIn(flightId, List.of("2A", "2B")))
                .allMatch(seat -> seat.getStatus() == SeatStatus.AVAILABLE);
    }

    @Test
    void releasingAHoldFreesItsSeats() {
        Long flightId = newFlight(5, "AB");
        SeatHoldView hold = holds.holdSeats(flightId, List.of("5A"), "hold-key-0009");

        holds.release(hold.holdId());
        holds.release(hold.holdId());

        assertThat(holds.hold(hold.holdId()).status()).isEqualTo(HoldStatus.RELEASED);
        assertThat(holds.holdSeats(flightId, List.of("5A"), "hold-key-0010").status()).isEqualTo(HoldStatus.ACTIVE);
    }

    @Test
    void expiryJobReturnsExpiredHoldsToSale() {
        Long flightId = newFlight(5, "AB");
        SeatHoldView hold = holds.holdSeats(flightId, List.of("1A"), "hold-key-0011");

        assertThat(expiryJob.expireDueHolds(clock.instant())).isZero();
        clock.advance(Duration.ofMinutes(15));
        assertThat(expiryJob.expireDueHolds(clock.instant())).isGreaterThanOrEqualTo(1);

        assertThat(holds.hold(hold.holdId()).status()).isEqualTo(HoldStatus.EXPIRED);
        assertThat(seats.findByFlightIdAndSeatNumberIn(flightId, List.of("1A")))
                .allMatch(seat -> seat.getStatus() == SeatStatus.AVAILABLE);
    }

    @Test
    void lateConfirmationSucceedsIfNobodyTookTheSeats() {
        Long flightId = newFlight(5, "AB");
        SeatHoldView hold = holds.holdSeats(flightId, List.of("3A"), "hold-key-0012");
        clock.advance(Duration.ofMinutes(20));
        expiryJob.expireDueHolds(clock.instant());

        assertThat(holds.confirm(hold.holdId(), "LATE22").status()).isEqualTo(HoldStatus.CONFIRMED);
    }

    @Test
    void lateConfirmationFailsIfTheSeatsWereResold() {
        Long flightId = newFlight(5, "AB");
        SeatHoldView hold = holds.holdSeats(flightId, List.of("3B"), "hold-key-0013");
        clock.advance(Duration.ofMinutes(20));
        holds.holdSeats(flightId, List.of("3B"), "hold-key-0014");

        assertThatThrownBy(() -> holds.confirm(hold.holdId(), "LATE33"))
                .hasFieldOrPropertyWithValue("errorCode", InventoryErrorCode.HOLD_EXPIRED);
    }

    @Test
    void searchReturnsAvailabilityAndHonoursPassengerCount() {
        Long flightId = newFlight(2, "AB");
        LocalDate date = clock.instant().plus(Duration.ofDays(10)).atZone(ZoneOffset.UTC).toLocalDate();
        holds.holdSeats(flightId, List.of("2A", "2B"), "hold-key-0015");

        FlightView view = queries.search("DXB", "LHR", date, 1).stream()
                .filter(f -> f.flight().getId().equals(flightId)).findFirst().orElseThrow();

        assertThat(view.availableBusiness()).isEqualTo(2);
        assertThat(view.availableEconomy()).isZero();
        assertThat(queries.search("DXB", "LHR", date, 3)).noneMatch(f -> f.flight().getId().equals(flightId));
    }

    @Test
    void flightCreationIsIdempotentAndRejectsDuplicates() {
        var departure = clock.instant().plus(Duration.ofDays(30));
        var command = new CreateFlightCommand("SB9001", "BOM", "DXB", departure, departure.plus(Duration.ofHours(3)),
                "A321", Money.of("150.00", "USD"), 3, "AB", 0);

        FlightView first = admin.createFlight(command, "create-key-0001");
        FlightView replay = admin.createFlight(command, "create-key-0001");

        assertThat(replay.flight().getId()).isEqualTo(first.flight().getId());
        assertThat(first.availableEconomy()).isEqualTo(6);
        assertThatThrownBy(() -> admin.createFlight(command, "create-key-0002"))
                .hasFieldOrPropertyWithValue("errorCode", InventoryErrorCode.DUPLICATE_FLIGHT);
        assertThat(admin.listFlights(0, 100).getContent())
                .anyMatch(f -> f.flight().getId().equals(first.flight().getId()));
    }

    @Test
    void cancelledFlightAcceptsNoHolds() {
        Long flightId = newFlight(2, "AB");

        admin.cancelFlight(flightId);

        assertThatThrownBy(() -> holds.holdSeats(flightId, List.of("1A"), "hold-key-0016"))
                .hasFieldOrPropertyWithValue("errorCode", InventoryErrorCode.FLIGHT_NOT_BOOKABLE);
    }
}
