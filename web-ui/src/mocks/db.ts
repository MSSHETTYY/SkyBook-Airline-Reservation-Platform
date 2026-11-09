/**
 * In-memory stand-in for inventory, booking and payment services, speaking the same wire format
 * as the OpenAPI specs. Used by unit tests (msw/node), `npm run dev:mock` and the Playwright suite
 * (msw/browser), so UI work and E2E runs need no Docker, Keycloak or Stripe.
 *
 * Business rules mirror the backend closely enough to exercise every screen: 15-minute holds,
 * refund tiers (100% >= 7 days, 50% >= 24 h, else 0), a 50 per passenger change fee, ticketing
 * after payment. Seat 3A is a deliberate trap: it shows as free but the first attempt to book it
 * fails with SEAT_UNAVAILABLE, as if another customer took it a moment earlier.
 */

export type Role = 'CUSTOMER' | 'AGENT' | 'ADMIN';

export interface WireMoney {
  amount: number;
  currency: string;
}

export interface WireSeat {
  seatNumber: string;
  cabinClass: 'ECONOMY' | 'BUSINESS';
  available: boolean;
  price: WireMoney;
}

export interface WireFlight {
  flightId: number;
  flightNumber: string;
  origin: string;
  destination: string;
  departureTime: string;
  arrivalTime: string;
  aircraftType: string;
  status: 'SCHEDULED' | 'CANCELLED';
  availableSeats: { economy: number; business: number };
  fromPrice: WireMoney;
}

export interface WirePassenger {
  firstName: string;
  lastName: string;
  seatNumber: string;
  ticketNumber?: string;
}

export interface WireBooking {
  bookingReference: string;
  status: string;
  flight: { flightId: number; flightNumber: string; origin: string; destination: string; departureTime: string };
  passengers: WirePassenger[];
  totalPrice: WireMoney;
  contactEmail: string;
  holdExpiresAt?: string;
  payment?: { paymentId: string; status: string; clientSecret?: string };
  amountPaid?: WireMoney;
  amountRefunded?: WireMoney;
  changeAmountDue?: WireMoney;
  createdAt: string;
}

export interface WireRefund {
  refundId: string;
  paymentId: string;
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED';
  amount: WireMoney;
  reason: string;
  createdAt: string;
}

export interface WirePayment {
  paymentId: string;
  bookingReference: string;
  purpose: 'BOOKING' | 'DATE_CHANGE';
  status: 'REQUIRES_PAYMENT' | 'SUCCEEDED' | 'CANCELLED';
  amount: WireMoney;
  amountRefunded: WireMoney;
  clientSecret?: string;
  failureReason?: string;
  refunds: WireRefund[];
  createdAt: string;
}

interface FlightRecord {
  flight: Omit<WireFlight, 'availableSeats' | 'fromPrice'>;
  baseFare: WireMoney;
  seats: WireSeat[];
}

interface BookingRecord {
  booking: WireBooking;
  owner: string;
  paidAt?: number;
  pendingChange?: { flightId: number; seats: string[]; total: WireMoney };
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    detail: string,
  ) {
    super(detail);
  }
}

export const ROUTES = [
  { flightNumber: 'SB101', origin: 'DXB', destination: 'LHR', hour: 8, minutes: 450, fare: 420 },
  { flightNumber: 'SB102', origin: 'LHR', destination: 'DXB', hour: 14, minutes: 420, fare: 410 },
  { flightNumber: 'SB201', origin: 'DXB', destination: 'BOM', hour: 9, minutes: 180, fare: 180 },
  { flightNumber: 'SB202', origin: 'BOM', destination: 'DXB', hour: 15, minutes: 195, fare: 175 },
  { flightNumber: 'SB301', origin: 'LHR', destination: 'JFK', hour: 11, minutes: 480, fare: 520 },
];

const REFERENCE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const BUSINESS_MULTIPLIER = 2.5;
const CHANGE_FEE_PER_PASSENGER = 50;
const HOLD_MINUTES = 15;
const DAY_MS = 86_400_000;

const money = (amount: number, currency = 'USD'): WireMoney => ({ amount: Math.round(amount * 100) / 100, currency });

function randomString(length: number, alphabet: string): string {
  let out = '';
  for (let i = 0; i < length; i++) out += alphabet[Math.floor(Math.random() * alphabet.length)];
  return out;
}

export interface MockDbOptions {
  /** How long after payment the "GDS" issues tickets (PAID -> CONFIRMED). */
  ticketingDelayMs?: number;
  days?: number;
  rows?: number;
  now?: () => number;
}

export class MockDb {
  private readonly flights = new Map<number, FlightRecord>();
  private readonly bookings = new Map<string, BookingRecord>();
  private readonly payments = new Map<string, WirePayment>();
  private readonly idempotency = new Map<string, { fingerprint: string; result: unknown }>();
  private trapArmed = new Set<number>();
  private nextFlightId = 1;
  readonly ticketingDelayMs: number;
  private readonly now: () => number;
  private readonly rows: number;

  constructor(options: MockDbOptions = {}) {
    this.ticketingDelayMs = options.ticketingDelayMs ?? 1500;
    this.now = options.now ?? Date.now;
    this.rows = options.rows ?? 12;
    const today = new Date(this.now());
    today.setUTCHours(0, 0, 0, 0);
    for (let day = 1; day <= (options.days ?? 14); day++) {
      for (const route of ROUTES) {
        const departure = new Date(today.getTime() + day * DAY_MS + route.hour * 3_600_000);
        this.addFlight({
          flightNumber: route.flightNumber,
          origin: route.origin,
          destination: route.destination,
          departureTime: departure.toISOString(),
          arrivalTime: new Date(departure.getTime() + route.minutes * 60_000).toISOString(),
          aircraftType: 'A320',
          baseFare: money(route.fare),
          layout: { rows: this.rows, seatLetters: 'ABCDEF', businessRows: 2 },
        });
      }
    }
  }

  // ---------------------------------------------------------------- inventory

  addFlight(input: {
    flightNumber: string;
    origin: string;
    destination: string;
    departureTime: string;
    arrivalTime: string;
    aircraftType: string;
    baseFare: WireMoney;
    layout: { rows: number; seatLetters: string; businessRows: number };
  }): WireFlight {
    const flightId = this.nextFlightId++;
    const seats: WireSeat[] = [];
    for (let row = 1; row <= input.layout.rows; row++) {
      for (const letter of input.layout.seatLetters) {
        const business = row <= input.layout.businessRows;
        const index = seats.length;
        seats.push({
          seatNumber: `${row}${letter}`,
          cabinClass: business ? 'BUSINESS' : 'ECONOMY',
          // A sprinkling of seats already sold, deterministic per flight.
          available: (index + flightId) % 7 !== 0,
          price: money(input.baseFare.amount * (business ? BUSINESS_MULTIPLIER : 1), input.baseFare.currency),
        });
      }
    }
    const trap = seats.find((s) => s.seatNumber === '3A');
    if (trap) {
      trap.available = true;
      this.trapArmed.add(flightId);
    }
    const { baseFare, layout: _layout, ...rest } = input;
    this.flights.set(flightId, { flight: { ...rest, flightId, status: 'SCHEDULED' }, baseFare, seats });
    return this.summary(flightId);
  }

  summary(flightId: number): WireFlight {
    const record = this.flightRecord(flightId);
    const free = record.seats.filter((s) => s.available);
    const cheapest = free.length ? Math.min(...free.map((s) => s.price.amount)) : record.baseFare.amount;
    return {
      ...record.flight,
      availableSeats: {
        economy: free.filter((s) => s.cabinClass === 'ECONOMY').length,
        business: free.filter((s) => s.cabinClass === 'BUSINESS').length,
      },
      fromPrice: money(cheapest, record.baseFare.currency),
    };
  }

  searchFlights(origin: string, destination: string, date: string, passengers: number): WireFlight[] {
    return [...this.flights.keys()]
      .map((id) => this.summary(id))
      .filter((f) => f.origin === origin && f.destination === destination && f.departureTime.startsWith(date))
      .filter((f) => f.status === 'SCHEDULED' && f.availableSeats.economy + f.availableSeats.business >= passengers)
      .sort((a, b) => a.departureTime.localeCompare(b.departureTime));
  }

  listFlights(page: number, size: number) {
    const all = [...this.flights.keys()].map((id) => this.summary(id));
    return { items: all.slice(page * size, page * size + size), page, size, totalElements: all.length };
  }

  seatMap(flightId: number) {
    return { flightId, seats: this.flightRecord(flightId).seats };
  }

  cancelFlight(flightId: number): WireFlight {
    this.flightRecord(flightId).flight.status = 'CANCELLED';
    return this.summary(flightId);
  }

  private flightRecord(flightId: number): FlightRecord {
    const record = this.flights.get(flightId);
    if (!record) throw new ApiError(404, 'RESOURCE_NOT_FOUND', `Flight ${flightId} not found`);
    return record;
  }

  private takeSeats(flightId: number, seatNumbers: string[]): WireSeat[] {
    const record = this.flightRecord(flightId);
    if (record.flight.status !== 'SCHEDULED') {
      throw new ApiError(422, 'FLIGHT_NOT_BOOKABLE', 'Flight is not open for booking');
    }
    if (new Set(seatNumbers).size !== seatNumbers.length) {
      throw new ApiError(422, 'DUPLICATE_SEAT', 'Each passenger needs a different seat');
    }
    const seats = seatNumbers.map((n) => {
      const seat = record.seats.find((s) => s.seatNumber === n);
      if (!seat) throw new ApiError(422, 'UNKNOWN_SEAT', `Seat ${n} does not exist on this flight`);
      return seat;
    });
    if (seatNumbers.includes('3A') && this.trapArmed.delete(flightId)) {
      seats.find((s) => s.seatNumber === '3A')!.available = false;
      throw new ApiError(409, 'SEAT_UNAVAILABLE', 'Seat 3A is no longer available');
    }
    const taken = seats.find((s) => !s.available);
    if (taken) throw new ApiError(409, 'SEAT_UNAVAILABLE', `Seat ${taken.seatNumber} is no longer available`);
    seats.forEach((s) => (s.available = false));
    return seats;
  }

  private releaseSeats(flightId: number, seatNumbers: string[]) {
    for (const seat of this.flightRecord(flightId).seats) {
      if (seatNumbers.includes(seat.seatNumber)) seat.available = true;
    }
  }

  // ---------------------------------------------------------------- bookings

  /** Same semantics as the backend: same key + same body = same answer; different body = 422. */
  idempotent<T>(scope: string, key: string | null, body: unknown, action: () => T): T {
    if (!key || key.length < 8) throw new ApiError(400, 'VALIDATION_FAILED', 'Idempotency-Key header is required');
    const fingerprint = JSON.stringify(body);
    const existing = this.idempotency.get(`${scope}:${key}`);
    if (existing) {
      if (existing.fingerprint !== fingerprint) {
        throw new ApiError(422, 'IDEMPOTENCY_KEY_REUSED', 'Idempotency key reused for a different request');
      }
      return existing.result as T;
    }
    const result = action();
    this.idempotency.set(`${scope}:${key}`, { fingerprint, result });
    return result;
  }

  createBooking(owner: string, request: {
    flightId: number;
    contactEmail: string;
    passengers: Array<{ firstName: string; lastName: string; dateOfBirth: string; seatNumber: string }>;
  }): WireBooking {
    const seats = this.takeSeats(request.flightId, request.passengers.map((p) => p.seatNumber));
    const flight = this.flightRecord(request.flightId).flight;
    const total = money(seats.reduce((sum, s) => sum + s.price.amount, 0), seats[0].price.currency);
    let reference = randomString(6, REFERENCE_ALPHABET);
    while (this.bookings.has(reference)) reference = randomString(6, REFERENCE_ALPHABET);
    const payment = this.newPayment(reference, 'BOOKING', total);
    const booking: WireBooking = {
      bookingReference: reference,
      status: 'PENDING_PAYMENT',
      flight: {
        flightId: flight.flightId,
        flightNumber: flight.flightNumber,
        origin: flight.origin,
        destination: flight.destination,
        departureTime: flight.departureTime,
      },
      passengers: request.passengers.map(({ firstName, lastName, seatNumber }) => ({ firstName, lastName, seatNumber })),
      totalPrice: total,
      contactEmail: request.contactEmail,
      holdExpiresAt: new Date(this.now() + HOLD_MINUTES * 60_000).toISOString(),
      payment: { paymentId: payment.paymentId, status: payment.status, clientSecret: payment.clientSecret },
      amountPaid: money(0, total.currency),
      amountRefunded: money(0, total.currency),
      createdAt: new Date(this.now()).toISOString(),
    };
    this.bookings.set(reference, { booking, owner });
    return booking;
  }

  /** Ticketing happens "in the background": a booking paid long enough ago is confirmed on read. */
  getBooking(reference: string, caller: { owner: string; staff: boolean }): WireBooking {
    const record = this.bookings.get(reference);
    if (!record || (!caller.staff && record.owner !== caller.owner)) {
      throw new ApiError(404, 'RESOURCE_NOT_FOUND', `Booking ${reference} not found`);
    }
    const { booking } = record;
    if (booking.status === 'PAID' && record.paidAt !== undefined
        && this.now() - record.paidAt >= this.ticketingDelayMs) {
      booking.status = 'CONFIRMED';
      booking.passengers = booking.passengers.map((p) => ({
        ...p,
        ticketNumber: p.ticketNumber ?? `176${randomString(10, '0123456789')}`,
      }));
    }
    return booking;
  }

  listBookings(filter: { owner?: string; status?: string; flightId?: number }, page: number, size: number) {
    // Newest first; reverse() first so bookings created in the same millisecond keep a stable order.
    const all = [...this.bookings.values()]
      .reverse()
      .filter((r) => filter.owner === undefined || r.owner === filter.owner)
      .map((r) => this.getBooking(r.booking.bookingReference, { owner: r.owner, staff: true }))
      .filter((b) => !filter.status || b.status === filter.status)
      .filter((b) => !filter.flightId || b.flight.flightId === filter.flightId)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    return { items: all.slice(page * size, page * size + size), page, size, totalElements: all.length };
  }

  cancellationQuote(reference: string, caller: { owner: string; staff: boolean }) {
    const booking = this.getBooking(reference, caller);
    if (booking.status !== 'CONFIRMED') {
      throw new ApiError(422, 'NOT_CANCELLABLE', 'The booking can no longer be cancelled');
    }
    const hours = (Date.parse(booking.flight.departureTime) - this.now()) / 3_600_000;
    const [percentage, rule] = hours >= 24 * 7
      ? [100, 'At least 7 days before departure: full refund']
      : hours >= 24
        ? [50, 'Between 24 hours and 7 days before departure: 50% refund']
        : [0, 'Less than 24 hours before departure: no refund'];
    const paid = booking.amountPaid?.amount ?? 0;
    return {
      bookingReference: reference,
      refundAmount: money((paid * percentage) / 100, booking.totalPrice.currency),
      refundPercentage: percentage,
      rule,
    };
  }

  cancelBooking(reference: string, caller: { owner: string; staff: boolean }): WireBooking {
    const quote = this.cancellationQuote(reference, caller);
    const booking = this.getBooking(reference, caller);
    this.releaseSeats(booking.flight.flightId, booking.passengers.map((p) => p.seatNumber));
    if (quote.refundAmount.amount > 0) this.recordRefund(reference, quote.refundAmount, 'CUSTOMER_CANCELLATION');
    booking.status = 'CANCELLED';
    booking.passengers = booking.passengers.map(({ ticketNumber: _t, ...p }) => p);
    return booking;
  }

  changeDate(reference: string, caller: { owner: string; staff: boolean },
             request: { newFlightId: number; seatNumbers: string[] }): WireBooking {
    const record = this.bookings.get(reference);
    const booking = this.getBooking(reference, caller);
    if (!record || booking.status !== 'CONFIRMED') {
      throw new ApiError(422, 'NOT_CHANGEABLE', 'The booking can no longer be changed');
    }
    if (request.seatNumbers.length !== booking.passengers.length) {
      throw new ApiError(400, 'VALIDATION_FAILED', 'One seat per passenger is required');
    }
    const seats = this.takeSeats(request.newFlightId, request.seatNumbers);
    const total = money(seats.reduce((sum, s) => sum + s.price.amount, 0), booking.totalPrice.currency);
    const due = total.amount - booking.totalPrice.amount + CHANGE_FEE_PER_PASSENGER * booking.passengers.length;
    if (due > 0) {
      const payment = this.newPayment(reference, 'DATE_CHANGE', money(due));
      record.pendingChange = { flightId: request.newFlightId, seats: request.seatNumbers, total };
      booking.status = 'CHANGE_PENDING_PAYMENT';
      booking.changeAmountDue = money(due);
      booking.payment = { paymentId: payment.paymentId, status: payment.status, clientSecret: payment.clientSecret };
    } else {
      this.applyChange(record, { flightId: request.newFlightId, seats: request.seatNumbers, total });
      if (due < 0) this.recordRefund(reference, money(-due), 'DATE_CHANGE_DIFFERENCE');
    }
    return booking;
  }

  private applyChange(record: BookingRecord, change: NonNullable<BookingRecord['pendingChange']>) {
    const { booking } = record;
    this.releaseSeats(booking.flight.flightId, booking.passengers.map((p) => p.seatNumber));
    const flight = this.flightRecord(change.flightId).flight;
    booking.flight = {
      flightId: flight.flightId,
      flightNumber: flight.flightNumber,
      origin: flight.origin,
      destination: flight.destination,
      departureTime: flight.departureTime,
    };
    booking.passengers = booking.passengers.map((p, i) => ({
      ...p,
      seatNumber: change.seats[i],
      ticketNumber: `176${randomString(10, '0123456789')}`,
    }));
    booking.totalPrice = change.total;
    booking.status = 'CONFIRMED';
    delete booking.changeAmountDue;
    record.pendingChange = undefined;
  }

  // ---------------------------------------------------------------- payments

  private newPayment(reference: string, purpose: WirePayment['purpose'], amount: WireMoney): WirePayment {
    const payment: WirePayment = {
      paymentId: `pay_${randomString(24, 'abcdefghijklmnopqrstuvwxyz0123456789')}`,
      bookingReference: reference,
      purpose,
      status: 'REQUIRES_PAYMENT',
      amount,
      amountRefunded: money(0, amount.currency),
      // The fake provider's secret: deliberately not a `pi_..._secret_...` value, so the UI offers simulation.
      clientSecret: `fake_secret_${randomString(16, 'abcdef0123456789')}`,
      refunds: [],
      createdAt: new Date(this.now()).toISOString(),
    };
    this.payments.set(payment.paymentId, payment);
    return payment;
  }

  simulatePayment(paymentId: string, succeed: boolean): void {
    const payment = this.payments.get(paymentId);
    if (!payment) throw new ApiError(404, 'RESOURCE_NOT_FOUND', `Payment ${paymentId} not found`);
    const record = this.bookings.get(payment.bookingReference)!;
    if (!succeed) {
      payment.failureReason = 'card_declined';
      return;
    }
    payment.status = 'SUCCEEDED';
    delete payment.clientSecret;
    const { booking } = record;
    booking.amountPaid = money((booking.amountPaid?.amount ?? 0) + payment.amount.amount, payment.amount.currency);
    booking.payment = { paymentId, status: 'SUCCEEDED' };
    if (payment.purpose === 'BOOKING') {
      booking.status = 'PAID';
      delete booking.holdExpiresAt;
      record.paidAt = this.now();
    } else if (record.pendingChange) {
      this.applyChange(record, record.pendingChange);
    }
  }

  paymentsFor(reference: string): WirePayment[] {
    return [...this.payments.values()]
      .filter((p) => p.bookingReference === reference)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }

  manualRefund(reference: string, amount: WireMoney): WireBooking {
    const booking = this.getBooking(reference, { owner: '', staff: true });
    const refundable = (booking.amountPaid?.amount ?? 0) - (booking.amountRefunded?.amount ?? 0);
    if (amount.amount > refundable + 1e-9) {
      throw new ApiError(422, 'REFUND_EXCEEDS_PAYMENT', 'Refund exceeds the amount paid');
    }
    this.recordRefund(reference, amount, 'MANUAL');
    return booking;
  }

  private recordRefund(reference: string, amount: WireMoney, reason: string) {
    const record = this.bookings.get(reference)!;
    const payment = this.paymentsFor(reference).find((p) => p.status === 'SUCCEEDED');
    if (payment) {
      payment.refunds.push({
        refundId: `re_${randomString(16, 'abcdefghijklmnopqrstuvwxyz0123456789')}`,
        paymentId: payment.paymentId,
        status: 'SUCCEEDED',
        amount,
        reason,
        createdAt: new Date(this.now()).toISOString(),
      });
      payment.amountRefunded = money(payment.amountRefunded.amount + amount.amount, amount.currency);
    }
    const b = record.booking;
    b.amountRefunded = money((b.amountRefunded?.amount ?? 0) + amount.amount, amount.currency);
  }
}
