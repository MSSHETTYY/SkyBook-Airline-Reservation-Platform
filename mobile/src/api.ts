import { config } from './config';
import * as booking from './api/generated/booking';
import * as inventory from './api/generated/inventory';
import * as inventoryV1 from './api/generated/inventory-v1';
import * as payment from './api/generated/payment';

/** RFC 9457 problem, reduced to what the screens show. */
export interface ApiProblem {
  status: number;
  title: string;
  detail?: string;
  code?: string;
}

let tokenProvider: () => Promise<string | undefined> = async () => undefined;

/** Set by the auth provider; returns a fresh access token (refreshing it when needed). */
export function setTokenProvider(provider: () => Promise<string | undefined>): void {
  tokenProvider = provider;
}

const middleware = {
  async pre(context: { url: string; init: RequestInit }) {
    const headers = new Headers(context.init.headers);
    const token = await tokenProvider();
    if (token) headers.set('Authorization', `Bearer ${token}`);
    return { url: context.url, init: { ...context.init, headers } };
  },
};

function configuration<T>(Config: new (params: object) => T): T {
  return new Config({ basePath: config.apiBaseUrl, middleware: [middleware] });
}

export const api = {
  offers: new inventory.FlightOffersApi(configuration(inventory.Configuration)),
  flights: new inventoryV1.FlightsApi(configuration(inventoryV1.Configuration)),
  bookings: new booking.BookingsApi(configuration(booking.Configuration)),
  simulation: new payment.SimulationApi(configuration(payment.Configuration)),
};

const FRIENDLY: Record<string, string> = {
  SEAT_UNAVAILABLE: 'Those seats were just taken. Please try again.',
  NOT_CANCELLABLE: 'This booking can no longer be cancelled.',
  UNAUTHENTICATED: 'Please sign in again.',
  SERVICE_UNAVAILABLE: 'SkyBook is temporarily unavailable. Please try again shortly.',
};

export async function toProblem(error: unknown): Promise<ApiProblem> {
  const response = (error as { response?: Response } | null)?.response;
  if (response && typeof response.status === 'number') {
    let body: Partial<ApiProblem> = {};
    try {
      body = (await response.json()) as Partial<ApiProblem>;
    } catch {
      // not JSON
    }
    return {
      status: response.status,
      title: body.title ?? 'Request failed',
      detail: (body.code && FRIENDLY[body.code]) || body.detail,
      code: body.code,
    };
  }
  return { status: 0, title: 'No connection', detail: 'Check your internet connection and try again.' };
}

/** Wraps generated-client calls so query errors are always ApiProblem. */
export async function call<T>(fn: () => Promise<T>): Promise<T> {
  try {
    return await fn();
  } catch (error) {
    throw await toProblem(error);
  }
}

export function newIdempotencyKey(): string {
  return `m-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

declare module '@tanstack/react-query' {
  interface Register {
    defaultError: ApiProblem;
  }
}
