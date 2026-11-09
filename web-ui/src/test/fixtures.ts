import type { MockDb } from '../mocks/db';

export const CUSTOMER_TOKEN = 'Bearer mock.CUSTOMER';

/** Free economy seats, avoiding the seat 3A conflict trap. */
export function freeSeats(db: MockDb, flightId: number, count: number): string[] {
  return db
    .seatMap(flightId)
    .seats.filter((s) => s.available && s.seatNumber !== '3A' && s.cabinClass === 'ECONOMY')
    .slice(0, count)
    .map((s) => s.seatNumber);
}

/** A flight departing at least `days` days from now (first match in schedule order). */
export function flightInDays(db: MockDb, days: number, route = 'DXB-LHR') {
  const [origin, destination] = route.split('-');
  return db
    .listFlights(0, 1000)
    .items.find((f) => f.origin === origin && f.destination === destination
      && Date.parse(f.departureTime) - Date.now() >= days * 86_400_000)!;
}

export function createBooking(db: MockDb, flightId: number, passengers = 1) {
  const seats = freeSeats(db, flightId, passengers);
  return db.createBooking(CUSTOMER_TOKEN, {
    flightId,
    contactEmail: 'customer@skybook.test',
    passengers: seats.map((seatNumber, i) => ({
      firstName: `Pax${'ABCDEFGHI'[i]}`,
      lastName: 'Test',
      dateOfBirth: '1990-01-01',
      seatNumber,
    })),
  });
}

export function confirmedBooking(db: MockDb, flightId: number, passengers = 1) {
  const booking = createBooking(db, flightId, passengers);
  db.simulatePayment(booking.payment!.paymentId, true);
  return db.getBooking(booking.bookingReference, { owner: CUSTOMER_TOKEN, staff: false });
}
