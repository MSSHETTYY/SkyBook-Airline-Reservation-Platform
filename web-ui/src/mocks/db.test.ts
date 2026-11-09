import { ApiError, MockDb } from './db';

const DAY = 86_400_000;
const OWNER = 'Bearer mock.CUSTOMER';
const caller = { owner: OWNER, staff: false };

function firstFreeSeats(db: MockDb, flightId: number, count: number): string[] {
  return db
    .seatMap(flightId)
    .seats.filter((s) => s.available && s.seatNumber !== '3A' && s.cabinClass === 'ECONOMY')
    .slice(0, count)
    .map((s) => s.seatNumber);
}

function confirmedBooking(db: MockDb, flightId: number) {
  const [seat] = firstFreeSeats(db, flightId, 1);
  const booking = db.createBooking(OWNER, {
    flightId,
    contactEmail: 'a@example.test',
    passengers: [{ firstName: 'Ada', lastName: 'Lovelace', dateOfBirth: '1990-01-01', seatNumber: seat }],
  });
  db.simulatePayment(booking.payment!.paymentId, true);
  return db.getBooking(booking.bookingReference, caller);
}

function expectApiError(fn: () => unknown, code: string) {
  try {
    fn();
  } catch (error) {
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe(code);
    return;
  }
  throw new Error(`expected ${code}`);
}

describe('MockDb', () => {
  const now = Date.parse('2026-06-01T00:00:00Z');
  let db: MockDb;
  beforeEach(() => {
    db = new MockDb({ ticketingDelayMs: 0, now: () => now, days: 10 });
  });

  it('confirms and tickets a booking once it is paid', () => {
    const booking = confirmedBooking(db, 1);
    expect(booking.status).toBe('CONFIRMED');
    expect(booking.passengers[0].ticketNumber).toMatch(/^176\d{10}$/);
    expect(booking.amountPaid?.amount).toBe(booking.totalPrice.amount);
  });

  it('applies the refund tiers by time to departure', () => {
    const flights = db.listFlights(0, 100).items;
    const at = (days: number) => flights.find((f) => Date.parse(f.departureTime) - now >= days * DAY)!;
    expect(db.cancellationQuote(confirmedBooking(db, at(8).flightId).bookingReference, caller).refundPercentage)
      .toBe(100);
    const soon = flights.find((f) => Date.parse(f.departureTime) - now < 2 * DAY
      && Date.parse(f.departureTime) - now >= DAY)!;
    expect(db.cancellationQuote(confirmedBooking(db, soon.flightId).bookingReference, caller).refundPercentage)
      .toBe(50);
  });

  it('springs the seat 3A trap exactly once', () => {
    const request = {
      flightId: 2,
      contactEmail: 'a@example.test',
      passengers: [{ firstName: 'A', lastName: 'B', dateOfBirth: '1990-01-01', seatNumber: '3A' }],
    };
    expectApiError(() => db.createBooking(OWNER, request), 'SEAT_UNAVAILABLE');
    expect(db.seatMap(2).seats.find((s) => s.seatNumber === '3A')!.available).toBe(false);
  });

  it('rejects duplicate and unknown seats and enforces ownership', () => {
    const base = { flightId: 1, contactEmail: 'a@example.test' };
    const p = (seatNumber: string) => ({ firstName: 'A', lastName: 'B', dateOfBirth: '1990-01-01', seatNumber });
    expectApiError(() => db.createBooking(OWNER, { ...base, passengers: [p('5B'), p('5B')] }), 'DUPLICATE_SEAT');
    expectApiError(() => db.createBooking(OWNER, { ...base, passengers: [p('99Z')] }), 'UNKNOWN_SEAT');
    const booking = confirmedBooking(db, 1);
    expectApiError(() => db.getBooking(booking.bookingReference, { owner: 'someone else', staff: false }),
      'RESOURCE_NOT_FOUND');
    expect(db.getBooking(booking.bookingReference, { owner: '', staff: true }).bookingReference)
      .toBe(booking.bookingReference);
  });

  it('replays idempotent requests and rejects a reused key with another body', () => {
    let calls = 0;
    const run = (body: object) => db.idempotent('s', 'key-12345', body, () => ++calls);
    expect(run({ a: 1 })).toBe(1);
    expect(run({ a: 1 })).toBe(1);
    expectApiError(() => run({ a: 2 }), 'IDEMPOTENCY_KEY_REUSED');
    expectApiError(() => db.idempotent('s', null, {}, () => 0), 'VALIDATION_FAILED');
  });

  it('charges the fare difference plus fee for a date change and applies it after payment', () => {
    const booking = confirmedBooking(db, 1);
    const later = db.searchFlights('DXB', 'LHR', db.summary(6).departureTime.slice(0, 10), 1)[0];
    const seats = db.seatMap(later.flightId).seats.filter((s) => s.available && s.cabinClass === 'BUSINESS');
    const changed = db.changeDate(booking.bookingReference, caller,
      { newFlightId: later.flightId, seatNumbers: [seats[0].seatNumber] });
    expect(changed.status).toBe('CHANGE_PENDING_PAYMENT');
    expect(changed.changeAmountDue!.amount).toBeCloseTo(seats[0].price.amount - booking.totalPrice.amount + 50);
    db.simulatePayment(changed.payment!.paymentId, true);
    const after = db.getBooking(booking.bookingReference, caller);
    expect(after.status).toBe('CONFIRMED');
    expect(after.flight.flightId).toBe(later.flightId);
  });

  it('limits manual refunds to what was paid', () => {
    const booking = confirmedBooking(db, 1);
    expectApiError(() => db.manualRefund(booking.bookingReference, { amount: 1e6, currency: 'USD' }),
      'REFUND_EXCEEDS_PAYMENT');
    const refunded = db.manualRefund(booking.bookingReference, { amount: 10, currency: 'USD' });
    expect(refunded.amountRefunded?.amount).toBe(10);
    expect(db.paymentsFor(booking.bookingReference)[0].refunds).toHaveLength(1);
  });
});
