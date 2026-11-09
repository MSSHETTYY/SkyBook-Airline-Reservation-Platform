import type { HttpResponseResolver } from 'msw';
import { delay, http, HttpResponse } from 'msw';

import type { MockDb, Role } from './db';
import { ApiError } from './db';

interface Caller {
  token: string;
  role: Role;
  staff: boolean;
}

/** Same split as the backend's GlobalProblemHandler: a stable title per code, the specifics in detail. */
const TITLES: Record<string, string> = {
  RESOURCE_NOT_FOUND: 'Resource not found',
  VALIDATION_FAILED: 'Request validation failed',
  IDEMPOTENCY_KEY_REUSED: 'Idempotency key reused for a different request',
  SEAT_UNAVAILABLE: 'Seat no longer available',
  UNAUTHENTICATED: 'Authentication required',
  ACCESS_DENIED: 'Access denied',
};

function problem(status: number, code: string, detail: string) {
  return HttpResponse.json(
    {
      type: `https://skybook.example/problems/${code.toLowerCase().replace(/_/g, '-')}`,
      title: TITLES[code] ?? detail,
      status,
      detail,
      code,
      correlationId: crypto.randomUUID(),
      timestamp: new Date().toISOString(),
    },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

/** `Authorization: Bearer mock.<ROLE>` (what MockSessionProvider sends). */
function callerOf(request: Request): Caller | undefined {
  const match = /^Bearer mock\.(CUSTOMER|AGENT|ADMIN)$/.exec(request.headers.get('Authorization') ?? '');
  if (!match) return undefined;
  const role = match[1] as Role;
  return { token: match[0], role, staff: role !== 'CUSTOMER' };
}

type Guard = 'public' | 'user' | 'staff' | 'admin';

/** Wraps a handler with the same 401/403 rules as the gateway and turns ApiError into problem JSON. */
function guarded(guard: Guard, resolver: (args: { request: Request; params: Record<string, string>;
  caller: Caller }) => Response | Promise<Response>): HttpResponseResolver {
  return async ({ request, params }) => {
    await delay(import.meta.env.MODE === 'test' ? 0 : 150);
    const caller = callerOf(request);
    if (guard !== 'public' && !caller) {
      return problem(401, 'UNAUTHENTICATED', 'A valid bearer token is required');
    }
    if ((guard === 'staff' && !caller!.staff) || (guard === 'admin' && caller!.role !== 'ADMIN')) {
      return problem(403, 'ACCESS_DENIED', 'You are not allowed to perform this action');
    }
    try {
      return await resolver({
        request,
        params: params as Record<string, string>,
        caller: caller ?? { token: '', role: 'CUSTOMER', staff: false },
      });
    } catch (error) {
      if (error instanceof ApiError) return problem(error.status, error.code, error.message);
      throw error;
    }
  };
}

const owner = (caller: Caller) => ({ owner: caller.token, staff: caller.staff });
const intParam = (url: URL, name: string, fallback: number) => Number(url.searchParams.get(name) ?? fallback);

export function createHandlers(db: MockDb) {
  return [
    http.get('*/api/v1/flights', guarded('public', ({ request }) => {
      const url = new URL(request.url);
      const flights = db.searchFlights(
        url.searchParams.get('origin') ?? '',
        url.searchParams.get('destination') ?? '',
        url.searchParams.get('date') ?? '',
        intParam(url, 'passengers', 1),
      );
      return HttpResponse.json(flights);
    })),
    http.get('*/api/v1/flights/:flightId', guarded('public', ({ params }) =>
      HttpResponse.json(db.summary(Number(params.flightId))))),
    http.get('*/api/v1/flights/:flightId/seats', guarded('public', ({ params }) =>
      HttpResponse.json(db.seatMap(Number(params.flightId))))),

    http.get('*/api/v1/admin/flights', guarded('staff', ({ request }) => {
      const url = new URL(request.url);
      return HttpResponse.json(db.listFlights(intParam(url, 'page', 0), intParam(url, 'size', 20)));
    })),
    http.post('*/api/v1/admin/flights', guarded('admin', async ({ request }) => {
      const body = (await request.json()) as Parameters<MockDb['addFlight']>[0];
      const flight = db.idempotent('flight', request.headers.get('Idempotency-Key'), body, () => db.addFlight(body));
      return HttpResponse.json(flight, { status: 201 });
    })),
    http.post('*/api/v1/admin/flights/:flightId/cancellation', guarded('admin', ({ params }) =>
      HttpResponse.json(db.cancelFlight(Number(params.flightId))))),

    http.get('*/api/v1/bookings', guarded('user', ({ request, caller }) => {
      const url = new URL(request.url);
      return HttpResponse.json(
        db.listBookings({ owner: caller.token }, intParam(url, 'page', 0), intParam(url, 'size', 20)),
      );
    })),
    http.post('*/api/v1/bookings', guarded('user', async ({ request, caller }) => {
      const body = (await request.json()) as Parameters<MockDb['createBooking']>[1];
      const booking = db.idempotent(`booking:${caller.token}`, request.headers.get('Idempotency-Key'), body,
        () => db.createBooking(caller.token, body));
      return HttpResponse.json(booking, { status: 201 });
    })),
    http.get('*/api/v1/bookings/:reference', guarded('user', ({ params, caller }) =>
      HttpResponse.json(db.getBooking(params.reference, owner(caller))))),
    http.get('*/api/v1/bookings/:reference/cancellation-quote', guarded('user', ({ params, caller }) =>
      HttpResponse.json(db.cancellationQuote(params.reference, owner(caller))))),
    http.post('*/api/v1/bookings/:reference/cancellation', guarded('user', ({ params, caller }) =>
      HttpResponse.json(db.cancelBooking(params.reference, owner(caller)), { status: 202 }))),
    http.post('*/api/v1/bookings/:reference/date-change', guarded('user', async ({ request, params, caller }) => {
      const body = (await request.json()) as { newFlightId: number; seatNumbers: string[] };
      const booking = db.idempotent(`change:${params.reference}`, request.headers.get('Idempotency-Key'), body,
        () => db.changeDate(params.reference, owner(caller), body));
      return HttpResponse.json(booking);
    })),

    http.get('*/api/v1/admin/bookings', guarded('staff', ({ request }) => {
      const url = new URL(request.url);
      const flightId = url.searchParams.get('flightId');
      return HttpResponse.json(db.listBookings(
        { status: url.searchParams.get('status') ?? undefined, flightId: flightId ? Number(flightId) : undefined },
        intParam(url, 'page', 0),
        intParam(url, 'size', 20),
      ));
    })),
    http.post('*/api/v1/admin/bookings/:reference/refunds', guarded('admin', async ({ request, params }) => {
      const body = (await request.json()) as { amount: { amount: number; currency: string }; note: string };
      const booking = db.idempotent(`refund:${params.reference}`, request.headers.get('Idempotency-Key'), body,
        () => db.manualRefund(params.reference, body.amount));
      return HttpResponse.json(booking, { status: 202 });
    })),

    http.post('*/api/v1/payments/:paymentId/simulation', guarded('user', async ({ request, params }) => {
      const body = (await request.json()) as { outcome: 'SUCCEEDED' | 'FAILED' };
      db.simulatePayment(params.paymentId, body.outcome === 'SUCCEEDED');
      return new HttpResponse(null, { status: 202 });
    })),
    http.get('*/api/v1/admin/payments', guarded('staff', ({ request }) => {
      const reference = new URL(request.url).searchParams.get('bookingReference') ?? '';
      return HttpResponse.json(db.paymentsFor(reference));
    })),
  ];
}
