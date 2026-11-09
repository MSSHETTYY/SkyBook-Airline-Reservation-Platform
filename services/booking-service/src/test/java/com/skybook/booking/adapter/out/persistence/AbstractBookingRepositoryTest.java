package com.skybook.booking.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.skybook.booking.application.port.out.BookingRepository;
import com.skybook.booking.application.port.out.BookingRepository.DuplicateBookingException;
import com.skybook.booking.domain.model.Booking;
import com.skybook.booking.domain.model.BookingReference;
import com.skybook.booking.domain.model.BookingStatus;
import com.skybook.booking.domain.model.FlightSnapshot;
import com.skybook.booking.domain.model.Passenger;
import com.skybook.booking.domain.model.PendingChange;
import com.skybook.booking.support.BookingTestSupport;
import com.skybook.common.money.Money;

/** Repository contract, run on H2 ({@link BookingRepositoryTest}) and MariaDB ({@code BookingRepositoryMariaDbIT}). */
abstract class AbstractBookingRepositoryTest extends BookingTestSupport {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final Instant NOW = START;
    private static final FlightSnapshot FLIGHT = new FlightSnapshot(70L, "SB101", "DXB", "LHR",
            NOW.plus(Duration.ofDays(10)));

    @Autowired
    private BookingRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private Booking newBooking(String customer) {
        int n = SEQUENCE.incrementAndGet();
        String reference = "R" + String.format("%05d", n).replace('0', 'A').replace('1', 'B');
        return Booking.create(new BookingReference(reference), customer, "ada@example.test", FLIGHT,
                List.of(new Passenger("Ada", "Lovelace", LocalDate.of(1990, 12, 10), "12A", Money.of("100.00", "USD"),
                                null),
                        new Passenger("Alan", "Turing", LocalDate.of(1992, 6, 23), "12B", Money.of("110.00", "USD"),
                                null)),
                "hold-" + n, NOW.plus(Duration.ofMinutes(15)), "key-" + n, "fp-" + n, NOW);
    }

    @Test
    void savesAndRestoresTheWholeAggregate() {
        Booking saved = repository.save(newBooking("repo-cust-1"));

        Booking loaded = repository.findByReference(saved.getReference().value()).orElseThrow();

        assertThat(loaded.getId()).isNotNull();
        assertThat(loaded.state()).usingRecursiveComparison().ignoringFields("version", "id")
                .isEqualTo(saved.state());
        assertThat(loaded.getPassengers()).extracting(Passenger::firstName).containsExactly("Ada", "Alan");
        assertThat(repository.findByCustomerAndIdempotencyKey("repo-cust-1", saved.state().idempotencyKey()))
                .isPresent();
    }

    @Test
    void updatesFollowTheAggregateThroughItsLifecycle() {
        Booking booking = repository.save(newBooking("repo-cust-2"));
        booking.attachPayment("pay-1", "secret", NOW);
        booking = repository.save(booking);
        booking.markPaid("pay-1", Money.of("210.00", "USD"), NOW);
        booking.confirm(List.of("T-1", "T-2"), NOW);
        booking = repository.save(booking);
        booking.startPaidChange(new PendingChange(new FlightSnapshot(71L, "SB101", "DXB", "LHR",
                NOW.plus(Duration.ofDays(12))), "hold-x", NOW.plus(Duration.ofMinutes(15)),
                List.of(new PendingChange.SeatAssignment("3A", Money.of("150.00", "USD")),
                        new PendingChange.SeatAssignment("3B", Money.of("150.00", "USD"))),
                Money.of("90.00", "USD"), "pay-2", false), "secret", NOW);
        repository.save(booking);

        Booking loaded = repository.findByReference(booking.getReference().value()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(BookingStatus.CHANGE_PENDING_PAYMENT);
        assertThat(loaded.getPassengers()).extracting(Passenger::ticketNumber).containsExactly("T-1", "T-2");
        assertThat(loaded.getPendingChange().newSeats()).extracting(PendingChange.SeatAssignment::seatNumber)
                .containsExactly("3A", "3B");
        assertThat(loaded.getPendingChange().amountDue()).isEqualTo(Money.of("90.00", "USD"));
        assertThat(loaded.getAmountPaid()).isEqualTo(Money.of("210.00", "USD"));

        loaded.abandonChange(NOW);
        Booking afterAbandon = repository.save(loaded);
        assertThat(repository.findByReference(afterAbandon.getReference().value()).orElseThrow().getPendingChange())
                .isNull();
    }

    @Test
    void personalDataIsEncryptedAtRest() {
        Booking saved = repository.save(newBooking("repo-cust-7"));
        String reference = saved.getReference().value();

        String email = jdbc.queryForObject("select contact_email from booking where reference = ?", String.class,
                reference);
        List<String> passengerColumns = jdbc.queryForList(
                "select concat(p.first_name, '|', p.last_name, '|', p.date_of_birth) from booking_passenger p"
                        + " join booking b on b.id = p.booking_id where b.reference = ?", String.class, reference);

        assertThat(email).startsWith("test1:").doesNotContain("ada", "example");
        assertThat(passengerColumns).hasSize(2).allSatisfy(row -> assertThat(row)
                .doesNotContain("Ada", "Lovelace", "Alan", "Turing", "1990", "1992"));
        assertThat(repository.findByReference(reference).orElseThrow().getContactEmail())
                .isEqualTo("ada@example.test");
    }

    @Test
    void staleUpdatesAreRejected() {
        Booking saved = repository.save(newBooking("repo-cust-3"));
        Booking copyA = repository.findByReference(saved.getReference().value()).orElseThrow();
        Booking copyB = repository.findByReference(saved.getReference().value()).orElseThrow();

        copyA.attachPayment("pay-a", "s", NOW);
        repository.save(copyA);
        copyB.attachPayment("pay-b", "s", NOW);

        assertThatThrownBy(() -> repository.save(copyB)).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void duplicateIdempotencyKeysAndReferencesAreReportedDistinctly() {
        Booking first = repository.save(newBooking("repo-cust-4"));
        var s = first.state();
        Booking sameKey = Booking.create(new BookingReference("DUPKEY"), s.customerId(), s.contactEmail(), s.flight(),
                s.passengers(), "h", NOW, s.idempotencyKey(), "fp", NOW);
        Booking sameReference = Booking.create(s.reference(), "repo-cust-5", s.contactEmail(), s.flight(),
                s.passengers(), "h", NOW, "other-key", "fp", NOW);

        assertThatThrownBy(() -> repository.save(sameKey)).isInstanceOfSatisfying(DuplicateBookingException.class,
                ex -> assertThat(ex.isDuplicateIdempotencyKey()).isTrue());
        assertThatThrownBy(() -> repository.save(sameReference)).isInstanceOfSatisfying(DuplicateBookingException.class,
                ex -> assertThat(ex.isDuplicateIdempotencyKey()).isFalse());
    }

    @Test
    void findsOverdueBookingsAndPagesCustomerHistory() {
        Booking overdue = repository.save(newBooking("repo-cust-6"));
        repository.save(newBooking("repo-cust-6"));

        assertThat(repository.findWithHoldExpiredBefore(BookingStatus.PENDING_PAYMENT, NOW.plus(Duration.ofMinutes(15)),
                1000)).contains(overdue.getReference().value());
        assertThat(repository.findWithHoldExpiredBefore(BookingStatus.PENDING_PAYMENT, NOW, 1000))
                .doesNotContain(overdue.getReference().value());

        var page = repository.findByCustomer("repo-cust-6", 0, 1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(repository.search(BookingStatus.PENDING_PAYMENT, 70L, 0, 100).items())
                .extracting(b -> b.getReference().value()).contains(overdue.getReference().value());
        assertThat(repository.search(null, null, 0, 1).totalElements()).isGreaterThanOrEqualTo(2);
    }
}
