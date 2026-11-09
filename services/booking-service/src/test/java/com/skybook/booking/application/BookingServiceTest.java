package com.skybook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.skybook.booking.application.port.in.CreateBookingUseCase.CreateBookingCommand;
import com.skybook.booking.application.port.in.CreateBookingUseCase.PassengerDetails;
import com.skybook.booking.application.port.in.PageResult;
import com.skybook.booking.application.port.out.BookingEventPublisher;
import com.skybook.booking.application.port.out.BookingRepository;
import com.skybook.booking.application.port.out.BookingRepository.DuplicateBookingException;
import com.skybook.booking.application.port.out.InventoryPort;
import com.skybook.booking.application.port.out.PaymentPort;
import com.skybook.booking.application.service.BookingService;
import com.skybook.booking.application.service.BookingServiceTestAccess;
import com.skybook.booking.domain.model.Booking;
import com.skybook.booking.domain.model.BookingErrorCode;
import com.skybook.booking.domain.model.BookingReference;
import com.skybook.booking.domain.model.BookingStatus;
import com.skybook.booking.domain.model.FlightSnapshot;
import com.skybook.booking.domain.model.Passenger;
import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.error.ResourceNotFoundException;
import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;
import com.skybook.common.test.MutableClock;

/** Application-service rules with the ports mocked: idempotency, compensation, access, expiry. */
class BookingServiceTest {

    private static final Instant NOW = Instant.parse("2026-12-01T08:00:00Z");

    private final BookingRepository repository = mock(BookingRepository.class);
    private final InventoryPort inventory = mock(InventoryPort.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final PaymentPort payments = mock(PaymentPort.class);
    private final BookingEventPublisher events = mock(BookingEventPublisher.class);
    private final BookingService service = new BookingService(repository, inventory, payments, events,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), new SplittableRandom(7), clock);

    private final InventoryPort.SeatHold hold = new InventoryPort.SeatHold("hold-1",
            new FlightSnapshot(7L, "SB101", "DXB", "LHR", NOW.plus(Duration.ofDays(10))),
            List.of(new InventoryPort.HeldSeat("12A", Money.of("100.00", "USD")),
                    new InventoryPort.HeldSeat("12B", Money.of("110.00", "USD"))),
            Money.of("210.00", "USD"), NOW.plus(Duration.ofMinutes(15)));

    private final CreateBookingCommand command = new CreateBookingCommand("customer-1", "client-key-1", 7L,
            "ada@example.test", List.of(
                    new PassengerDetails("Ada", "Lovelace", LocalDate.of(1990, 12, 10), "12A"),
                    new PassengerDetails("Alan", "Turing", LocalDate.of(1992, 6, 23), "12B")));

    @BeforeEach
    void setUp() {
        when(payments.createPayment(anyString(), any(), any(), anyString()))
                .thenReturn(new PaymentPort.PaymentIntent("pay_123", "secret_123"));
        when(repository.findByCustomerAndIdempotencyKey(anyString(), anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> {
            Booking booking = inv.getArgument(0);
            booking.assignId(1L);
            return booking;
        });
    }

    @Test
    void createsBookingFromTheHoldWithLockedPrices() {
        when(inventory.holdSeats(eq(7L), anyCollection(), anyString())).thenReturn(hold);

        Booking booking = service.create(command);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(booking.getTotalPrice()).isEqualTo(Money.of("210.00", "USD"));
        assertThat(booking.getPassengers().get(1).seatPrice()).isEqualTo(Money.of("110.00", "USD"));
        assertThat(booking.getHoldExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(booking.getFlight().flightNumber()).isEqualTo("SB101");
        assertThat(booking.getPaymentId()).isEqualTo("pay_123");
        assertThat(booking.getPaymentClientSecret()).isEqualTo("secret_123");
        verify(events).bookingCreated(any());
        verify(events).seatsHeld(any());
        verify(inventory).holdSeats(7L, List.of("12A", "12B"), BookingServiceTestAccess.inventoryKey(command));
    }

    @Test
    void retryWithTheSameKeyReplaysWithoutTouchingInventory() {
        when(inventory.holdSeats(anyLong(), anyCollection(), anyString())).thenReturn(hold);
        Booking first = service.create(command);
        when(repository.findByCustomerAndIdempotencyKey("customer-1", "client-key-1")).thenReturn(Optional.of(first));

        Booking replay = service.create(command);

        assertThat(replay).isSameAs(first);
        verify(inventory, times(1)).holdSeats(anyLong(), anyCollection(), anyString());
    }

    @Test
    void sameKeyWithADifferentRequestIsRejected() {
        when(inventory.holdSeats(anyLong(), anyCollection(), anyString())).thenReturn(hold);
        Booking first = service.create(command);
        when(repository.findByCustomerAndIdempotencyKey("customer-1", "client-key-1")).thenReturn(Optional.of(first));
        CreateBookingCommand different = new CreateBookingCommand("customer-1", "client-key-1", 8L,
                "ada@example.test", command.passengers());

        assertThatThrownBy(() -> service.create(different))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void duplicateSeatsAreRejectedBeforeCallingInventory() {
        CreateBookingCommand duplicate = new CreateBookingCommand("customer-1", "client-key-2", 7L, "a@x.test",
                List.of(command.passengers().get(0), command.passengers().get(0)));

        assertThatThrownBy(() -> service.create(duplicate))
                .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.DUPLICATE_SEAT);
        verify(inventory, never()).holdSeats(anyLong(), anyCollection(), anyString());
    }

    @Test
    void inventoryRejectionPropagatesUnchanged() {
        SkyBookException unavailable = new SkyBookException(BookingErrorCode.SEAT_UNAVAILABLE, "12A taken");
        when(inventory.holdSeats(anyLong(), anyCollection(), anyString())).thenThrow(unavailable);

        assertThatThrownBy(() -> service.create(command)).isSameAs(unavailable);
        verify(repository, never()).save(any());
    }

    @Test
    void holdIsReleasedWhenTheBookingCannotBeStored() {
        when(inventory.holdSeats(anyLong(), anyCollection(), anyString())).thenReturn(hold);
        doThrow(new IllegalStateException("database down")).when(repository).save(any());
        // Even a failing release must not mask the original error: the hold then simply expires.
        doThrow(new SkyBookException(CommonErrorCode.SERVICE_UNAVAILABLE, "down"))
                .when(inventory).releaseHold("hold-1");

        assertThatThrownBy(() -> service.create(command)).hasMessage("database down");
        verify(inventory).releaseHold("hold-1");
    }

    @Test
    void referenceCollisionsAreRetriedWithANewReference() {
        when(inventory.holdSeats(anyLong(), anyCollection(), anyString())).thenReturn(hold);
        doThrow(new DuplicateBookingException(false, null)).doAnswer(inv -> inv.getArgument(0))
                .when(repository).save(any());

        assertThat(service.create(command).getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        // two attempts to insert (collision, success) + one update attaching the payment
        verify(repository, times(3)).save(any());
    }

    @Test
    void concurrentDuplicateRequestReplaysTheWinnerAndKeepsTheSharedHold() {
        when(inventory.holdSeats(anyLong(), anyCollection(), anyString())).thenReturn(hold);
        Booking winner = Booking.create(new BookingReference("WNNR22"), "customer-1", "ada@example.test",
                hold.flight(), List.of(new Passenger("Ada", "Lovelace", LocalDate.of(1990, 12, 10), "12A",
                        Money.of("100.00", "USD"), null)), "hold-1", hold.expiresAt(), "client-key-1",
                BookingServiceTestAccess.fingerprint(command), NOW);
        winner.attachPayment("pay_winner", "secret", NOW);
        doThrow(new DuplicateBookingException(true, null)).when(repository).save(any());
        when(repository.findByCustomerAndIdempotencyKey("customer-1", "client-key-1"))
                .thenReturn(Optional.empty(), Optional.of(winner));

        assertThat(service.create(command)).isSameAs(winner);
        verify(inventory, never()).releaseHold(anyString());
    }

    @Test
    void paymentOutageStillReturnsTheBookingAndARetryAttachesThePayment() {
        when(inventory.holdSeats(anyLong(), anyCollection(), anyString())).thenReturn(hold);
        when(payments.createPayment(anyString(), any(), any(), anyString()))
                .thenThrow(new SkyBookException(CommonErrorCode.SERVICE_UNAVAILABLE, "payments down"))
                .thenReturn(new PaymentPort.PaymentIntent("pay_late", "secret_late"));

        Booking first = service.create(command);
        assertThat(first.getPaymentId()).isNull();
        assertThat(first.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);

        when(repository.findByCustomerAndIdempotencyKey("customer-1", "client-key-1")).thenReturn(Optional.of(first));
        assertThat(service.create(command).getPaymentId()).isEqualTo("pay_late");
    }

    @Test
    void customersSeeOnlyTheirOwnBookingsStaffSeeAll() {
        when(inventory.holdSeats(anyLong(), anyCollection(), anyString())).thenReturn(hold);
        Booking booking = service.create(command);
        String reference = booking.getReference().value();
        when(repository.findByReference(reference)).thenReturn(Optional.of(booking));

        assertThat(service.get(reference, "customer-1", false)).isSameAs(booking);
        assertThat(service.get(reference, "agent-7", true)).isSameAs(booking);
        assertThatThrownBy(() -> service.get(reference, "customer-2", false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listAndSearchDelegateToTheRepository() {
        PageResult<Booking> empty = new PageResult<>(List.of(), 0, 20, 0);
        when(repository.findByCustomer("customer-1", 0, 20)).thenReturn(empty);
        when(repository.search(BookingStatus.CONFIRMED, 7L, 1, 10)).thenReturn(empty);

        assertThat(service.listForCustomer("customer-1", 0, 20)).isSameAs(empty);
        assertThat(service.search(BookingStatus.CONFIRMED, 7L, 1, 10)).isSameAs(empty);
    }

    @Test
    void overdueBookingsAreExpiredAndTheirHoldsReleased() {
        when(inventory.holdSeats(anyLong(), anyCollection(), anyString())).thenReturn(hold);
        Booking booking = service.create(command);
        String reference = booking.getReference().value();
        Instant later = NOW.plus(Duration.ofMinutes(16));
        when(repository.findWithHoldExpiredBefore(BookingStatus.PENDING_PAYMENT, later, 100))
                .thenReturn(List.of(reference, "GONE22"));
        when(repository.findByReference(reference)).thenReturn(Optional.of(booking));
        when(repository.findByReference("GONE22")).thenReturn(Optional.empty());

        assertThat(service.expireOverdue(later)).isEqualTo(1);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        verify(inventory).releaseHold("hold-1");
        verify(payments).cancelPayment("pay_123");
        verify(events).bookingCancelled(any(), eq("PAYMENT_TIMEOUT"), any());
    }

    @Test
    void bookingPaidConcurrentlyIsNotExpired() {
        when(inventory.holdSeats(anyLong(), anyCollection(), anyString())).thenReturn(hold);
        Booking booking = service.create(command);
        Instant later = NOW.plus(Duration.ofMinutes(16));
        when(repository.findWithHoldExpiredBefore(any(), any(), any(Integer.class)))
                .thenReturn(List.of(booking.getReference().value()));
        when(repository.findByReference(anyString())).thenReturn(Optional.of(booking));
        doThrow(new ObjectOptimisticLockingFailureException(Booking.class, 1L)).when(repository).save(any());

        assertThat(service.expireOverdue(later)).isZero();
        verify(inventory, never()).releaseHold(anyString());
    }
}
