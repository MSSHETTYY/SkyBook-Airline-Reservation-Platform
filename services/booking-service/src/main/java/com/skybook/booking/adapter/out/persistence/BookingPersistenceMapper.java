package com.skybook.booking.adapter.out.persistence;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.skybook.booking.domain.model.Booking;
import com.skybook.booking.domain.model.BookingReference;
import com.skybook.booking.domain.model.BookingState;
import com.skybook.booking.domain.model.BookingStatus;
import com.skybook.booking.domain.model.FlightSnapshot;
import com.skybook.booking.domain.model.Passenger;
import com.skybook.booking.domain.model.PendingChange;
import com.skybook.common.money.Money;

/** Copies state between the domain aggregate and the JPA entities. */
final class BookingPersistenceMapper {

    private BookingPersistenceMapper() {
    }

    static Booking toDomain(BookingEntity e) {
        String currency = e.getCurrency();
        List<Passenger> passengers = e.getPassengers().stream()
                .map(p -> new Passenger(p.getFirstName(), p.getLastName(), LocalDate.parse(p.getDateOfBirth()),
                        p.getSeatNumber(), Money.of(p.getSeatPrice(), currency), p.getTicketNumber()))
                .toList();
        return Booking.restore(new BookingState(
                e.getId(),
                new BookingReference(e.getReference()),
                e.getCustomerId(),
                e.getContactEmail(),
                BookingStatus.valueOf(e.getStatus()),
                new FlightSnapshot(e.getFlightId(), e.getFlightNumber(), e.getOrigin(), e.getDestination(),
                        e.getDepartureTime()),
                passengers,
                Money.of(e.getTotalAmount(), currency),
                e.getHoldId(),
                e.getHoldExpiresAt(),
                e.getPaymentId(),
                e.getPaymentClientSecret(),
                Money.of(e.getAmountPaid(), currency),
                Money.of(e.getAmountRefunded(), currency),
                toDomain(e.getPendingChange(), currency),
                e.getIdempotencyKey(),
                e.getRequestFingerprint(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getVersion()));
    }

    static BookingEntity newEntity(BookingState s) {
        BookingEntity entity = new BookingEntity(s.reference().value(), s.customerId(), s.idempotencyKey(),
                s.requestFingerprint(), s.createdAt());
        apply(s, entity);
        return entity;
    }

    /** Copies mutable state onto a managed entity; passengers are updated in place to keep their rows. */
    static void apply(BookingState s, BookingEntity e) {
        e.setContactEmail(s.contactEmail());
        e.setStatus(s.status().name());
        FlightSnapshot f = s.flight();
        e.setFlight(f.flightId(), f.flightNumber(), f.origin(), f.destination(), f.departureTime());
        e.setTotal(s.totalPrice().amount(), s.totalPrice().currencyCode());
        e.setHold(s.holdId(), s.holdExpiresAt());
        e.setPayment(s.paymentId(), s.paymentClientSecret());
        e.setAmounts(s.amountPaid().amount(), s.amountRefunded().amount());
        e.setUpdatedAt(s.updatedAt());

        List<PassengerEntity> rows = e.getPassengers();
        for (int i = 0; i < s.passengers().size(); i++) {
            if (i == rows.size()) {
                rows.add(new PassengerEntity(e, i));
            }
            Passenger p = s.passengers().get(i);
            PassengerEntity row = rows.get(i);
            row.setFirstName(p.firstName());
            row.setLastName(p.lastName());
            row.setDateOfBirth(p.dateOfBirth().toString());
            row.setSeatNumber(p.seatNumber());
            row.setSeatPrice(p.seatPrice().amount());
            row.setTicketNumber(p.ticketNumber());
        }
        while (rows.size() > s.passengers().size()) {
            rows.removeLast();
        }

        PendingChange change = s.pendingChange();
        if (change == null) {
            e.setPendingChange(null);
        } else {
            PendingChangeEntity row = e.getPendingChange() == null ? new PendingChangeEntity(e) : e.getPendingChange();
            FlightSnapshot nf = change.newFlight();
            row.update(nf.flightId(), nf.flightNumber(), nf.origin(), nf.destination(), nf.departureTime(),
                    change.holdId(), change.holdExpiresAt(), encodeSeats(change.newSeats()),
                    change.amountDue().amount(), change.paymentId());
            e.setPendingChange(row);
        }
    }

    private static PendingChange toDomain(PendingChangeEntity row, String currency) {
        if (row == null) {
            return null;
        }
        return new PendingChange(
                new FlightSnapshot(row.getNewFlightId(), row.getNewFlightNumber(), row.getNewOrigin(),
                        row.getNewDestination(), row.getNewDepartureTime()),
                row.getHoldId(), row.getHoldExpiresAt(), decodeSeats(row.getNewSeats(), currency),
                Money.of(row.getAmountDue(), currency), row.getPaymentId());
    }

    static String encodeSeats(List<PendingChange.SeatAssignment> seats) {
        return String.join(",", seats.stream()
                .map(seat -> seat.seatNumber() + ":" + seat.price().amount().toPlainString())
                .toList());
    }

    static List<PendingChange.SeatAssignment> decodeSeats(String encoded, String currency) {
        List<PendingChange.SeatAssignment> seats = new ArrayList<>();
        Arrays.stream(encoded.split(",")).forEach(entry -> {
            String[] parts = entry.split(":");
            seats.add(new PendingChange.SeatAssignment(parts[0], Money.of(parts[1], currency)));
        });
        return seats;
    }
}
