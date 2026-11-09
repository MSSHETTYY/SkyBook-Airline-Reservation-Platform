import { config } from '../config';
import * as booking from './generated/booking';
import * as inventory from './generated/inventory';
import * as payment from './generated/payment';

/**
 * One place that wires the generated OpenAPI clients to the gateway: base URL, bearer token,
 * correlation id. Components never call `fetch` directly, so every request gets the same headers.
 */

type TokenProvider = () => string | undefined;

let tokenProvider: TokenProvider = () => undefined;

/** Called by the auth provider whenever the session changes. */
export function setAccessTokenProvider(provider: TokenProvider): void {
  tokenProvider = provider;
}

const headersMiddleware = {
  async pre(context: { url: string; init: RequestInit }) {
    const headers = new Headers(context.init.headers);
    const token = tokenProvider();
    if (token) headers.set('Authorization', `Bearer ${token}`);
    // Echoed back in problem responses and logs, so a user-reported error can be traced end to end.
    headers.set('X-Correlation-Id', crypto.randomUUID());
    return { url: context.url, init: { ...context.init, headers } };
  },
};

function configuration<T>(Config: new (params: object) => T): T {
  // Generated paths already start with /api/v1, so the base path is just the gateway origin.
  // Absolute (location.origin) because the generated runtime builds URLs by string concatenation
  // and MSW in Node needs absolute URLs.
  const basePath = config.apiBaseUrl || globalThis.location?.origin || 'http://localhost';
  return new Config({ basePath, middleware: [headersMiddleware] });
}

const inventoryConfig = configuration(inventory.Configuration);
const bookingConfig = configuration(booking.Configuration);
const paymentConfig = configuration(payment.Configuration);

export const api = {
  flights: new inventory.FlightsApi(inventoryConfig),
  adminFlights: new inventory.AdminFlightsApi(inventoryConfig),
  bookings: new booking.BookingsApi(bookingConfig),
  adminBookings: new booking.AdminBookingsApi(bookingConfig),
  payments: new payment.PaymentsApi(paymentConfig),
  simulation: new payment.SimulationApi(paymentConfig),
  adminPayments: new payment.AdminPaymentsApi(paymentConfig),
};

/** Idempotency keys: one per user intent (a form submission), reused when that intent is retried. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
