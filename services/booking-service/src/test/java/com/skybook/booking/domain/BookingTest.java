package com.skybook.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.skybook.booking.domain.model.Booking;
import com.skybook.booking.domain.model.BookingErrorCode;
import com.skybook.booking.domain.model.BookingReference;
import com.skybook.booking.domain.model.BookingStatus;
import com.skybook.booking.domain.model.FlightSnapshot;
import com.skybook.booking.domain.model.Passenger;
import com.skybook.booking.domain.model.PendingChange;
import com.skybook.common.money.Money;

class BookingTest {

    private static final Instant NOW = Instant.parse("2026-12-01T08:00:00Z");
    private static final FlightSnapshot FLIGHT =
            new FlightSnapshot(7L, "SB101", "DXB", "LHR", NOW.plus(Duration.ofDays(10)));
    private static final FlightSnapshot OTHER_FLIGHT =
            new FlightSnapshot(8L, "SB101", "DXB", "LHR", NOW.plus(Duration.ofDays(12)));

    static Passenger passenger(String seat, String price) {
        return new Passenger("Ada", "Lovelace", LocalDate.of(1990, 12, 10), seat, Money.of(price, "USD"), null);
    }

    static Booking newBooking() {
        return Booking.create(new BookingReference("K7QX2M"), "customer-1", "ada@example.test", FLIGHT,
                List.of(passenger("12A", "100.00"), passenger("12B", "110.00")), "hold-1",
                NOW.plus(Duration.ofMinutes(15)), "key-1", "fp-1", NOW);
    }

    static Booking paidBooking() {
        Booking booking = newBooking();
        booking.attachPayment("pay-1", "secret", NOW);
        booking.markPaid("pay-1", Money.of("210.00", "USD"), NOW);
        return booking;
    }

    static Booking confirmedBooking() {
        Booking booking = paidBooking();
        booking.confirm(List.of("T-1", "T-2"), NOW);
        return booking;
    }

    @Test
    void createdBookingAwaitsPaymentAndTotalsSeatPrices() {
        Booking booking = newBooking();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(booking.getTotalPrice()).isEqualTo(Money.of("210.00", "USD"));
        assertThat(booking.seatNumbers()).containsExactly("12A", "12B");
        assertThat(booking.isOwnedBy("customer-1")).isTrue();
        assertThat(booking.isOwnedBy("customer-2")).isFalse();
        assertThat(booking.matchesRequest("fp-1")).isTrue();
        assertThat(booking.getAmountPaid().isZero()).isTrue();
    }

    @Test
    void rejectsDuplicateSeatsAndEmptyPassengerLists() {
        assertThatThrownBy(() -> Booking.create(new BookingReference("K7QX2M"), "c", "e@x.test", FLIGHT,
                List.of(passenger("1A", "1"), passenger("1A", "1")), "h", NOW, "k", "f", NOW))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.DUPLICATE_SEAT);
        assertThatIllegalArgumentException().isThrownBy(() -> Booking.create(new BookingReference("K7QX2M"), "c",
                "e@x.test", FLIGHT, List.of(), "h", NOW, "k", "f", NOW));
    }

    @Test
    void expiresOnlyWhenTheHoldRanOutUnpaid() {
        Booking booking = newBooking();

        assertThat(booking.isPaymentOverdue(NOW.plus(Duration.ofMinutes(14)))).isFalse();
        assertThatThrownBy(() -> booking.expire(NOW)).hasFieldOrPropertyWithValue("errorCode",
                BookingErrorCode.INVALID_STATE);

        booking.expire(NOW.plus(Duration.ofMinutes(15)));
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(booking.getStatus().isTerminal()).isTrue();
    }

    @Test
    void paymentFlowIsIdempotentAndChecksThePaymentId() {
        Booking booking = newBooking();
        booking.attachPayment("pay-1", "secret", NOW);

        assertThatThrownBy(() -> booking.markPaid("pay-other", Money.of("210", "USD"), NOW))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.INVALID_STATE);
        assertThat(booking.markPaid("pay-1", Money.of("210.00", "USD"), NOW)).isTrue();
        assertThat(booking.markPaid("pay-1", Money.of("210.00", "USD"), NOW)).as("duplicate event").isFalse();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PAID);
        assertThat(booking.getPaymentClientSecret()).isNull();
        assertThat(booking.getAmountPaid()).isEqualTo(Money.of("210.00", "USD"));
        assertThatThrownBy(() -> booking.attachPayment("pay-2", "s", NOW))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.INVALID_STATE);
        assertThatThrownBy(() -> booking.markPaid("pay-2", Money.of("1", "USD"), NOW))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.INVALID_STATE);
    }

    @Test
    void confirmationAssignsTicketsInPassengerOrder() {
        Booking booking = confirmedBooking();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getPassengers()).extracting(Passenger::ticketNumber).containsExactly("T-1", "T-2");
        assertThat(booking.getHoldExpiresAt()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> paidBooking().confirm(List.of("T-1"), NOW));
        assertThatThrownBy(() -> newBooking().confirm(List.of("T-1", "T-2"), NOW))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.INVALID_STATE);
    }

    @Test
    void failedTicketingIsTerminal() {
        Booking booking = paidBooking();

        booking.fail(NOW);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.FAILED);
        assertThatThrownBy(() -> newBooking().fail(NOW))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.INVALID_STATE);
    }

    @Test
    void cancellationOfConfirmedBookingsAndRefundLimits() {
        Booking booking = confirmedBooking();

        booking.requestCancellation(Money.of("200.00", "USD"), NOW);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLATION_PENDING);
        assertThat(booking.isCancellationRefundComplete()).isFalse();
        booking.recordRefund(Money.of("200.00", "USD"), NOW);
        assertThat(booking.isCancellationRefundComplete()).isTrue();
        assertThat(booking.refundableAmount()).isEqualTo(Money.of("10.00", "USD"));
        assertThatThrownBy(() -> booking.recordRefund(Money.of("10.01", "USD"), NOW))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.REFUND_EXCEEDS_PAYMENT);
        assertThatIllegalArgumentException().isThrownBy(() -> booking.recordRefund(Money.of("0", "USD"), NOW));
        booking.markCancelled(NOW);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void cannotCancelUnconfirmedOrDepartedBookings() {
        assertThatThrownBy(() -> newBooking().requestCancellation(Money.of("0", "USD"), NOW))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.NOT_CANCELLABLE);
        assertThatThrownBy(() -> confirmedBooking().requestCancellation(Money.of("1", "USD"),
                FLIGHT.departureTime()))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.NOT_CANCELLABLE);
    }

    @Test
    void unpaidBookingCanBeAbandoned() {
        Booking booking = newBooking();
        booking.attachPayment("pay-1", "secret", NOW);

        booking.cancelUnpaid(NOW);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getPaymentClientSecret()).isNull();
        assertThatThrownBy(() -> confirmedBooking().cancelUnpaid(NOW))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.INVALID_STATE);
    }

    @Test
    void freeDateChangeMovesPassengersAndReprices() {
        Booking booking = confirmedBooking();

        FlightSnapshot previous = booking.applyChange(OTHER_FLIGHT, "hold-2",
                List.of(seat("3A", "90.00"), seat("3B", "90.00")), Money.zero(booking.getTotalPrice().currency()),
                List.of("T-3", "T-4"), NOW);

        assertThat(previous).isEqualTo(FLIGHT);
        assertThat(booking.getFlight()).isEqualTo(OTHER_FLIGHT);
        assertThat(booking.seatNumbers()).containsExactly("3A", "3B");
        assertThat(booking.getTotalPrice()).isEqualTo(Money.of("180.00", "USD"));
        assertThat(booking.getHoldId()).isEqualTo("hold-2");
        assertThat(booking.getPassengers()).extracting(Passenger::ticketNumber).containsExactly("T-3", "T-4");
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void paidDateChangeWaitsForPaymentThenApplies() {
        Booking booking = confirmedBooking();
        PendingChange change = new PendingChange(OTHER_FLIGHT, "hold-2", NOW.plus(Duration.ofMinutes(15)),
                List.of(seat("3A", "150.00"), seat("3B", "150.00")), Money.of("140.00", "USD"), null, false)
                .withPayment("pay-2");

        booking.startPaidChange(change, "change-secret", NOW);
        assertThat(booking.getPaymentClientSecret()).isEqualTo("change-secret");
        assertThatThrownBy(() -> booking.markChangePaid("pay-other", NOW))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.INVALID_STATE);
        assertThat(booking.markChangePaid("pay-2", NOW)).isTrue();
        assertThat(booking.markChangePaid("pay-2", NOW)).isFalse();
        assertThat(booking.getPendingChange().paid()).isTrue();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CHANGE_PENDING_PAYMENT);
        assertThat(booking.getPendingChange().paymentId()).isEqualTo("pay-2");

        booking.applyChange(change.newFlight(), change.holdId(), change.newSeats(), change.amountDue(),
                List.of("T-3", "T-4"), NOW);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getPendingChange()).isNull();
        assertThat(booking.getAmountPaid()).isEqualTo(Money.of("350.00", "USD"));
    }

    @Test
    void abandonedChangeRestoresTheConfirmedBooking() {
        Booking booking = confirmedBooking();
        PendingChange change = new PendingChange(OTHER_FLIGHT, "hold-2", NOW, List.of(seat("3A", "1"),
                seat("3B", "1")), Money.of("10.00", "USD"), "pay-2", false);
        booking.startPaidChange(change, "secret", NOW);
        assertThat(booking.isPendingChangeOverdue(NOW)).isTrue();

        assertThat(booking.abandonChange(NOW)).isEqualTo(change);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getFlight()).isEqualTo(FLIGHT);
        assertThatThrownBy(() -> booking.abandonChange(NOW))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.INVALID_STATE);
    }

    @Test
    void changesAreRejectedWhenNotAllowed() {
        assertThatThrownBy(() -> newBooking().startPaidChange(new PendingChange(OTHER_FLIGHT, "h", NOW,
                List.of(), Money.of("1", "USD"), null, false), null, NOW))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.NOT_CHANGEABLE);
        assertThatIllegalArgumentException().isThrownBy(() -> confirmedBooking().startPaidChange(
                new PendingChange(OTHER_FLIGHT, "h", NOW, List.of(), Money.of("0", "USD"), null, false), null,
                NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> confirmedBooking().requestCancellation(
                Money.of("999.00", "USD"), NOW));
        assertThatThrownBy(() -> newBooking().applyChange(OTHER_FLIGHT, "h", List.of(), Money.of("0", "USD"),
                List.of(), NOW)).hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.INVALID_STATE);
        assertThatIllegalArgumentException().isThrownBy(() -> confirmedBooking().applyChange(OTHER_FLIGHT, "h",
                List.of(seat("1A", "1")), Money.of("0", "USD"), List.of("T"), NOW));
        assertThatThrownBy(() -> confirmedBooking().applyChange(OTHER_FLIGHT, "h", List.of(seat("1A", "1"),
                seat("1B", "1")), Money.of("0", "USD"), List.of("T", "U"), FLIGHT.departureTime()))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.NOT_CHANGEABLE);
    }

    @Test
    void stateRoundTripRestoresAnEqualAggregate() {
        Booking original = confirmedBooking();
        original.assignId(42L);

        Booking restored = Booking.restore(original.state());

        assertThat(restored.state()).isEqualTo(original.state());
        assertThat(restored.getId()).isEqualTo(42L);
        assertThatThrownBy(() -> restored.assignId(43L)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"CANCELLED", "EXPIRED", "FAILED"})
    void terminalStatusesAllowNoTransitions(BookingStatus status) {
        assertThat(status.isTerminal()).isTrue();
        assertThat(status.allowedNext()).isEmpty();
    }

    @Test
    void bookingReferencesUseAnUnambiguousAlphabet() {
        SplittableRandom random = new SplittableRandom(1);
        for (int i = 0; i < 1_000; i++) {
            assertThat(BookingReference.random(random).value()).matches("[A-HJKMNP-Z2-9]{6}");
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new BookingReference("K7QX2O"));
        assertThatIllegalArgumentException().isThrownBy(() -> new BookingReference("SHORT"));
        assertThat(new BookingReference("K7QX2M")).hasToString("K7QX2M");
    }

    @Test
    void passengersNeverPrintPersonalData() {
        assertThat(passenger("1A", "1").toString()).doesNotContain("Ada", "Lovelace", "1990");
        assertThatIllegalArgumentException().isThrownBy(() -> new Passenger(" ", "x", LocalDate.EPOCH, "1A",
                Money.of("1", "USD"), null));
    }

    private static PendingChange.SeatAssignment seat(String number, String price) {
        return new PendingChange.SeatAssignment(number, Money.of(price, "USD"));
    }
}
