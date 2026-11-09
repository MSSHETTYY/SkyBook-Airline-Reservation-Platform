package com.skybook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.skybook.booking.application.port.in.ChangeBookingUseCase.ChangeDateCommand;
import com.skybook.booking.application.port.out.BookingEventPublisher;
import com.skybook.booking.application.port.out.CustomerProfilePort;
import com.skybook.booking.application.port.out.InventoryPort;
import com.skybook.booking.application.port.out.LoyaltyPort;
import com.skybook.booking.application.port.out.PaymentPort;
import com.skybook.booking.application.port.out.TicketingPort;
import com.skybook.booking.application.service.BookingSagaService;
import com.skybook.booking.domain.model.Booking;
import com.skybook.booking.domain.model.BookingErrorCode;
import com.skybook.booking.domain.model.BookingReference;
import com.skybook.booking.domain.model.BookingStatus;
import com.skybook.booking.domain.model.CustomerTier;
import com.skybook.booking.domain.model.FlightSnapshot;
import com.skybook.booking.domain.model.Passenger;
import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.error.ResourceNotFoundException;
import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;
import com.skybook.common.test.MutableClock;

/** The orchestrated saga, step by step, including every compensation path. */
class BookingSagaServiceTest {

    private static final Instant NOW = Instant.parse("2026-12-01T08:00:00Z");
    private static final String REF = "K7QX2M";
    private static final FlightSnapshot FLIGHT = new FlightSnapshot(7L, "SB101", "DXB", "LHR",
            NOW.plus(Duration.ofDays(10)));
    private static final FlightSnapshot NEW_FLIGHT = new FlightSnapshot(8L, "SB101", "DXB", "LHR",
            NOW.plus(Duration.ofDays(12)));

    private final InMemoryBookingRepository bookings = new InMemoryBookingRepository();
    private final InventoryPort inventory = mock(InventoryPort.class);
    private final PaymentPort payments = mock(PaymentPort.class);
    private final TicketingPort ticketing = mock(TicketingPort.class);
    private final LoyaltyPort loyalty = mock(LoyaltyPort.class);
    private final CustomerProfilePort customers = mock(CustomerProfilePort.class);
    private final BookingEventPublisher events = mock(BookingEventPublisher.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final BookingSagaService saga = new BookingSagaService(bookings, inventory, payments, ticketing, loyalty,
            customers, events, new TransactionTemplate(mock(PlatformTransactionManager.class)), clock);

    @BeforeEach
    void setUp() {
        when(customers.tierOf(anyString())).thenReturn(CustomerTier.STANDARD);
        when(ticketing.issueTickets(anyString(), any(), anyList())).thenReturn(List.of("T-1", "T-2"));
    }

    private static Passenger passenger(String first, String seat) {
        return new Passenger(first, "Traveller", LocalDate.of(1990, 1, 1), seat, Money.of("100.00", "USD"), null);
    }

    private Booking awaitingPayment() {
        Booking booking = Booking.create(new BookingReference(REF), "cust-1", "ada@example.test", FLIGHT,
                List.of(passenger("Ada", "12A"), passenger("Alan", "12B")), "hold-1",
                NOW.plus(Duration.ofMinutes(15)), "key", "fp", NOW);
        booking.attachPayment("pay-1", "secret", NOW);
        return bookings.save(booking);
    }

    private Booking confirmed() {
        awaitingPayment();
        saga.recordPaymentCompleted(REF, "pay-1", "BOOKING", Money.of("200.00", "USD"));
        saga.resume(REF);
        return bookings.findByReference(REF).orElseThrow();
    }

    private Booking current() {
        return bookings.findByReference(REF).orElseThrow();
    }

    // ------------------------------------------------------------------------------------- happy path

    @Test
    void paymentLeadsToTicketsConfirmedSeatsAndMiles() {
        Booking booking = confirmed();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.ticketNumbers()).containsExactly("T-1", "T-2");
        verify(inventory).confirmHold("hold-1", REF);
        verify(events).ticketIssued(any());
        verify(loyalty).accrueMiles("cust-1", REF, Money.of("200.00", "USD"));
    }

    @Test
    void duplicatePaymentEventsAndRepeatedResumesAreHarmless() {
        confirmed();

        saga.recordPaymentCompleted(REF, "pay-1", "BOOKING", Money.of("200.00", "USD"));
        saga.resume(REF);

        assertThat(current().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(events, org.mockito.Mockito.times(1)).ticketIssued(any());
    }

    // ------------------------------------------------------------------------------------- ticketing failures

    @Test
    void transientGdsOutageIsRetriedByRecoveryBeforeTheDeadline() {
        awaitingPayment();
        saga.recordPaymentCompleted(REF, "pay-1", "BOOKING", Money.of("200.00", "USD"));
        when(ticketing.issueTickets(anyString(), any(), anyList()))
                .thenThrow(new SkyBookException(CommonErrorCode.SERVICE_UNAVAILABLE, "gds down"))
                .thenReturn(List.of("T-1", "T-2"));

        saga.resume(REF);
        assertThat(current().getStatus()).isEqualTo(BookingStatus.PAID);

        clock.advance(Duration.ofMinutes(1));
        assertThat(saga.recoverStalledSagas(clock.instant())).isEqualTo(1);
        assertThat(current().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(payments, never()).refundBooking(anyString(), any(), any(), anyString());
    }

    @Test
    void outageBeyondTheDeadlineIsCompensatedWithAFullRefund() {
        awaitingPayment();
        saga.recordPaymentCompleted(REF, "pay-1", "BOOKING", Money.of("200.00", "USD"));
        when(ticketing.issueTickets(anyString(), any(), anyList()))
                .thenThrow(new SkyBookException(CommonErrorCode.SERVICE_UNAVAILABLE, "gds down"));
        clock.advance(Duration.ofMinutes(11));

        saga.resume(REF);

        assertThat(current().getStatus()).isEqualTo(BookingStatus.FAILED);
        verify(inventory).releaseHold("hold-1");
        verify(payments).refundBooking(REF, Money.of("200.00", "USD"), PaymentPort.RefundReason.TICKETING_FAILED,
                "compensate-" + REF);
        verify(events).bookingCancelled(any(), eq("TICKETING_FAILED"), eq(Money.of("200.00", "USD")));
    }

    @Test
    void seatsLostToALatePaymentAreCompensatedAndIssuedTicketsVoided() {
        awaitingPayment();
        saga.recordPaymentCompleted(REF, "pay-1", "BOOKING", Money.of("200.00", "USD"));
        doThrow(new SkyBookException(BookingErrorCode.HOLD_EXPIRED, "taken")).when(inventory)
                .confirmHold("hold-1", REF);

        saga.resume(REF);

        assertThat(current().getStatus()).isEqualTo(BookingStatus.FAILED);
        verify(ticketing).voidTickets(REF, List.of("T-1", "T-2"));
        verify(payments).refundBooking(eq(REF), eq(Money.of("200.00", "USD")), any(), anyString());
    }

    @Test
    void failedRefundDuringCompensationLeavesTheSagaForRecovery() {
        awaitingPayment();
        saga.recordPaymentCompleted(REF, "pay-1", "BOOKING", Money.of("200.00", "USD"));
        doThrow(new SkyBookException(BookingErrorCode.HOLD_EXPIRED, "taken")).when(inventory)
                .confirmHold(anyString(), anyString());
        doThrow(new SkyBookException(CommonErrorCode.SERVICE_UNAVAILABLE, "payments down")).when(payments)
                .refundBooking(anyString(), any(), any(), anyString());

        assertThatThrownBy(() -> saga.resume(REF)).hasFieldOrPropertyWithValue("errorCode",
                CommonErrorCode.SERVICE_UNAVAILABLE);
        assertThat(current().getStatus()).isEqualTo(BookingStatus.PAID);
        clock.advance(Duration.ofMinutes(1));
        assertThat(saga.recoverStalledSagas(clock.instant())).as("logged, retried next round").isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------- cancellation

    @Test
    void earlyCancellationRefundsInFullAndCompletesOnRefundIssued() {
        confirmed();

        assertThat(saga.quoteCancellation(REF, "cust-1", false).percentage()).isEqualTo(100);
        Booking pending = saga.cancel(REF, "cust-1", false);

        assertThat(pending.getStatus()).isEqualTo(BookingStatus.CANCELLATION_PENDING);
        verify(ticketing).voidTickets(REF, List.of("T-1", "T-2"));
        verify(inventory).releaseBookedSeats(7L, REF);
        verify(payments).refundBooking(REF, Money.of("200.00", "USD"), PaymentPort.RefundReason.CUSTOMER_CANCELLATION,
                "cancel-" + REF);

        saga.recordRefundIssued(REF, Money.of("150.00", "USD"));
        assertThat(current().getStatus()).as("partial refund so far").isEqualTo(BookingStatus.CANCELLATION_PENDING);
        saga.recordRefundIssued(REF, Money.of("50.00", "USD"));
        assertThat(current().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(events).bookingCancelled(any(), eq("CUSTOMER_REQUEST"), eq(Money.of("200.00", "USD")));
    }

    @Test
    void cancellationWithinAWeekRefundsHalf() {
        confirmed();
        clock.set(FLIGHT.departureTime().minus(Duration.ofDays(3)));

        var quote = saga.quoteCancellation(REF, "cust-1", false);

        assertThat(quote.percentage()).isEqualTo(50);
        assertThat(quote.amount()).isEqualTo(Money.of("100.00", "USD"));
    }

    @Test
    void lastMinuteCancellationHasNoRefundAndCompletesAtOnce() {
        confirmed();
        clock.set(FLIGHT.departureTime().minus(Duration.ofHours(5)));

        Booking cancelled = saga.cancel(REF, "cust-1", false);

        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(payments, never()).refundBooking(anyString(), any(), any(), anyString());
        verify(events).bookingCancelled(any(), eq("CUSTOMER_REQUEST"), eq(Money.of("0", "USD")));
    }

    @Test
    void otherCustomersCannotCancelOrQuote() {
        confirmed();

        assertThatThrownBy(() -> saga.cancel(REF, "intruder", false)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> saga.quoteCancellation(REF, "intruder", false))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(saga.quoteCancellation(REF, "agent-1", true).percentage()).isEqualTo(100);
    }

    @Test
    void unconfirmedBookingsCannotBeQuoted() {
        awaitingPayment();

        assertThatThrownBy(() -> saga.quoteCancellation(REF, "cust-1", false))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.NOT_CANCELLABLE);
    }

    // ------------------------------------------------------------------------------------- date change

    private void stubNewHold(String pricePerSeat) {
        when(inventory.holdSeats(eq(8L), anyCollection(), anyString())).thenReturn(new InventoryPort.SeatHold(
                "hold-2", NEW_FLIGHT, List.of(new InventoryPort.HeldSeat("3A", Money.of(pricePerSeat, "USD")),
                new InventoryPort.HeldSeat("3B", Money.of(pricePerSeat, "USD"))),
                Money.of(pricePerSeat, "USD").times(2), NOW.plus(Duration.ofMinutes(15))));
    }

    private ChangeDateCommand changeTo(Long flightId, List<String> seats) {
        return new ChangeDateCommand(REF, "cust-1", false, flightId, seats, "change-key-1");
    }

    @Test
    void cheaperChangeAppliesAtOnceAndRefundsTheDifferenceMinusFees() {
        confirmed();
        stubNewHold("20.00");

        Booking changed = saga.changeDate(changeTo(8L, List.of("3A", "3B")));

        assertThat(changed.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(changed.getFlight()).isEqualTo(NEW_FLIGHT);
        assertThat(changed.seatNumbers()).containsExactly("3A", "3B");
        verify(inventory).confirmHold("hold-2", REF);
        verify(inventory).releaseBookedSeats(7L, REF);
        verify(events).bookingChanged(any(), eq(FLIGHT));
        // 200 old - 40 new - 2 x 50 fee = 60 back
        verify(payments).refundBooking(eq(REF), eq(Money.of("60.00", "USD")),
                eq(PaymentPort.RefundReason.DATE_CHANGE_DIFFERENCE), anyString());
    }

    @Test
    void dearerChangeWaitsForPaymentThenApplies() {
        confirmed();
        stubNewHold("150.00");
        when(payments.createPayment(eq(REF), any(), eq(PaymentPort.Purpose.DATE_CHANGE), anyString()))
                .thenReturn(new PaymentPort.PaymentIntent("pay-2", "secret-2"));

        Booking pending = saga.changeDate(changeTo(8L, List.of("3A", "3B")));

        // 300 new + 100 fee - 200 old = 200 due
        assertThat(pending.getStatus()).isEqualTo(BookingStatus.CHANGE_PENDING_PAYMENT);
        assertThat(pending.getPendingChange().amountDue()).isEqualTo(Money.of("200.00", "USD"));
        assertThat(pending.getPaymentClientSecret()).isEqualTo("secret-2");
        verify(inventory, never()).confirmHold(eq("hold-2"), anyString());

        saga.recordPaymentCompleted(REF, "pay-2", "DATE_CHANGE", Money.of("200.00", "USD"));
        saga.resume(REF);

        Booking applied = current();
        assertThat(applied.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(applied.getAmountPaid()).isEqualTo(Money.of("400.00", "USD"));
        assertThat(applied.getFlight()).isEqualTo(NEW_FLIGHT);
        verify(inventory).releaseBookedSeats(7L, REF);
    }

    @Test
    void goldMembersPayNoChangeFee() {
        confirmed();
        stubNewHold("100.00");
        when(customers.tierOf("cust-1")).thenReturn(CustomerTier.GOLD);

        Booking changed = saga.changeDate(changeTo(8L, List.of("3A", "3B")));

        assertThat(changed.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(payments, never()).createPayment(anyString(), any(), any(), anyString());
        verify(payments, never()).refundBooking(anyString(), any(), any(), anyString());
    }

    @Test
    void unpaidChangeExpiresAndKeepsTheOriginalFlight() {
        confirmed();
        stubNewHold("150.00");
        when(payments.createPayment(anyString(), any(), any(), anyString()))
                .thenReturn(new PaymentPort.PaymentIntent("pay-2", "secret-2"));
        saga.changeDate(changeTo(8L, List.of("3A", "3B")));

        clock.advance(Duration.ofMinutes(16));
        saga.recoverStalledSagas(clock.instant());

        assertThat(current().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(current().getFlight()).isEqualTo(FLIGHT);
        verify(payments).cancelPayment("pay-2");
        verify(inventory).releaseHold("hold-2");
    }

    @Test
    void paidChangeThatCannotBeAppliedIsRefunded() {
        confirmed();
        stubNewHold("150.00");
        when(payments.createPayment(anyString(), any(), any(), anyString()))
                .thenReturn(new PaymentPort.PaymentIntent("pay-2", "secret-2"));
        saga.changeDate(changeTo(8L, List.of("3A", "3B")));
        doThrow(new SkyBookException(BookingErrorCode.HOLD_EXPIRED, "taken")).when(inventory)
                .confirmHold("hold-2", REF);

        saga.recordPaymentCompleted(REF, "pay-2", "DATE_CHANGE", Money.of("200.00", "USD"));
        saga.resume(REF);

        assertThat(current().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(current().getFlight()).isEqualTo(FLIGHT);
        verify(payments).refundBooking(eq(REF), eq(Money.of("200.00", "USD")),
                eq(PaymentPort.RefundReason.DATE_CHANGE_DIFFERENCE), anyString());
    }

    @Test
    void changeRequestsAreValidated() {
        confirmed();

        assertThatThrownBy(() -> saga.changeDate(changeTo(7L, List.of("3A", "3B"))))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.NOT_CHANGEABLE);
        assertThatThrownBy(() -> saga.changeDate(changeTo(8L, List.of("3A"))))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> saga.changeDate(new ChangeDateCommand(REF, "intruder", false, 8L,
                List.of("3A", "3B"), "k"))).isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------------------------- manual refunds

    @Test
    void manualRefundsAreBoundedByWhatWasPaid() {
        confirmed();

        saga.refund(REF, Money.of("50.00", "USD"), "Goodwill for delay", "manual-key-1");

        verify(payments).refundBooking(REF, Money.of("50.00", "USD"), PaymentPort.RefundReason.MANUAL,
                "manual-manual-key-1");
        assertThatThrownBy(() -> saga.refund(REF, Money.of("200.01", "USD"), "too much", "k2"))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.REFUND_EXCEEDS_PAYMENT);
        saga.recordRefundIssued(REF, Money.of("50.00", "USD"));
        assertThat(current().getAmountRefunded()).isEqualTo(Money.of("50.00", "USD"));
        assertThat(current().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void resumingASettledBookingDoesNothing() {
        confirmed();

        saga.resume(REF);

        verify(ticketing, org.mockito.Mockito.times(1)).issueTickets(anyString(), any(), anyList());
    }
}
