import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { fromIsoDate } from '../lib/format';
import { api } from './client';
import type { ApiProblem } from './problem';
import { call } from './problem';
import type { Booking, CreateBookingRequest, CreateFlightRequest } from './types';
import { BookingStatus, SimulationRequestOutcomeEnum } from './types';

/**
 * Server state lives in TanStack Query: caching, de-duplication, retries and polling are declared
 * here once instead of being re-implemented with useEffect in every page.
 */

export const queryKeys = {
  flightSearch: (params: FlightSearchParams) => ['flights', 'search', params] as const,
  flight: (flightId: number) => ['flights', flightId] as const,
  seatMap: (flightId: number) => ['flights', flightId, 'seats'] as const,
  booking: (reference: string) => ['bookings', reference] as const,
  myBookings: (page: number) => ['bookings', 'mine', page] as const,
  cancellationQuote: (reference: string) => ['bookings', reference, 'cancellation-quote'] as const,
  adminFlights: (page: number) => ['admin', 'flights', page] as const,
  adminBookings: (filter: AdminBookingFilter) => ['admin', 'bookings', filter] as const,
  payments: (reference: string) => ['admin', 'payments', reference] as const,
};

export interface FlightSearchParams {
  origin: string;
  destination: string;
  date: string;
  passengers: number;
}

export interface AdminBookingFilter {
  status?: BookingStatus;
  flightId?: number;
  page: number;
}

/** States that change on their own (the saga is still running), so the page keeps polling. */
const TRANSIENT: ReadonlySet<BookingStatus> = new Set([
  BookingStatus.PENDING_PAYMENT,
  BookingStatus.PAID,
  BookingStatus.CHANGE_PENDING_PAYMENT,
  BookingStatus.CANCELLATION_PENDING,
]);

export const POLL_INTERVAL_MS = 2_000;

export function isTransient(status: BookingStatus): boolean {
  return TRANSIENT.has(status);
}

export function useFlightSearch(params: FlightSearchParams | undefined) {
  return useQuery({
    queryKey: params ? queryKeys.flightSearch(params) : ['flights', 'search', 'idle'],
    queryFn: () =>
      call(() =>
        api.flights.searchFlights({
          origin: params!.origin,
          destination: params!.destination,
          date: fromIsoDate(params!.date),
          passengers: params!.passengers,
        }),
      ),
    enabled: params !== undefined,
    staleTime: 30_000,
  });
}

export function useFlight(flightId: number) {
  return useQuery({
    queryKey: queryKeys.flight(flightId),
    queryFn: () => call(() => api.flights.getFlight({ flightId })),
    enabled: flightId > 0,
  });
}

export function useSeatMap(flightId: number) {
  return useQuery({
    queryKey: queryKeys.seatMap(flightId),
    queryFn: () => call(() => api.flights.getSeatMap({ flightId })),
    enabled: flightId > 0,
    // Availability changes as others book; refresh when the user comes back to the tab.
    staleTime: 5_000,
    refetchOnWindowFocus: true,
  });
}

export function useBooking(reference: string) {
  return useQuery({
    queryKey: queryKeys.booking(reference),
    queryFn: () => call(() => api.bookings.getBooking({ bookingReference: reference })),
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status && isTransient(status) ? POLL_INTERVAL_MS : false;
    },
  });
}

export function useMyBookings(page: number) {
  return useQuery({
    queryKey: queryKeys.myBookings(page),
    queryFn: () => call(() => api.bookings.listMyBookings({ page, size: 10 })),
    placeholderData: keepPreviousData,
  });
}

export function useCancellationQuote(reference: string, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.cancellationQuote(reference),
    queryFn: () => call(() => api.bookings.getCancellationQuote({ bookingReference: reference })),
    enabled,
    staleTime: 0,
  });
}

/** Writes the server's answer into the cache so the details page shows it without another request. */
function useStoreBooking() {
  const client = useQueryClient();
  return (booking: Booking) => {
    client.setQueryData(queryKeys.booking(booking.bookingReference), booking);
    void client.invalidateQueries({ queryKey: ['bookings', 'mine'] });
  };
}

export function useCreateBooking() {
  const store = useStoreBooking();
  return useMutation<Booking, ApiProblem, { request: CreateBookingRequest; idempotencyKey: string }>({
    mutationFn: ({ request, idempotencyKey }) =>
      call(() => api.bookings.createBooking({ createBookingRequest: request, idempotencyKey })),
    onSuccess: store,
  });
}

export function useCancelBooking(reference: string) {
  const store = useStoreBooking();
  return useMutation<Booking, ApiProblem, void>({
    mutationFn: () => call(() => api.bookings.cancelBooking({ bookingReference: reference })),
    onSuccess: store,
  });
}

export function useChangeBookingDate(reference: string) {
  const store = useStoreBooking();
  return useMutation<Booking, ApiProblem, { newFlightId: number; seatNumbers: string[]; idempotencyKey: string }>({
    mutationFn: ({ newFlightId, seatNumbers, idempotencyKey }) =>
      call(() =>
        api.bookings.changeBookingDate({
          bookingReference: reference,
          idempotencyKey,
          dateChangeRequest: { newFlightId, seatNumbers },
        }),
      ),
    onSuccess: store,
  });
}

/** Fake payment provider only: completes or declines the payment through payment-service's signed webhook. */
export function useSimulatePayment(reference: string) {
  const client = useQueryClient();
  return useMutation<void, ApiProblem, { paymentId: string; succeed: boolean }>({
    mutationFn: ({ paymentId, succeed }) =>
      call(() =>
        api.simulation.simulatePaymentOutcome({
          paymentId,
          simulationRequest: {
            outcome: succeed ? SimulationRequestOutcomeEnum.SUCCEEDED : SimulationRequestOutcomeEnum.FAILED,
          },
        }),
      ),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.booking(reference) }),
  });
}

export function useAdminFlights(page: number) {
  return useQuery({
    queryKey: queryKeys.adminFlights(page),
    queryFn: () => call(() => api.adminFlights.listFlights({ page, size: 20 })),
    placeholderData: keepPreviousData,
  });
}

export function useCreateFlight() {
  const client = useQueryClient();
  return useMutation<unknown, ApiProblem, { request: CreateFlightRequest; idempotencyKey: string }>({
    mutationFn: ({ request, idempotencyKey }) =>
      call(() => api.adminFlights.createFlight({ createFlightRequest: request, idempotencyKey })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['admin', 'flights'] }),
  });
}

export function useCancelFlight() {
  const client = useQueryClient();
  return useMutation<unknown, ApiProblem, number>({
    mutationFn: (flightId) => call(() => api.adminFlights.cancelFlight({ flightId })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['admin', 'flights'] }),
  });
}

export function useAdminBookings(filter: AdminBookingFilter) {
  return useQuery({
    queryKey: queryKeys.adminBookings(filter),
    queryFn: () =>
      call(() =>
        api.adminBookings.searchBookings({
          status: filter.status,
          flightId: filter.flightId,
          page: filter.page,
          size: 20,
        }),
      ),
    placeholderData: keepPreviousData,
  });
}

export function usePaymentsForBooking(reference: string | undefined) {
  return useQuery({
    queryKey: queryKeys.payments(reference ?? ''),
    queryFn: () => call(() => api.adminPayments.listPaymentsForBooking({ bookingReference: reference! })),
    enabled: reference !== undefined,
  });
}

export function useManualRefund(reference: string) {
  const client = useQueryClient();
  const store = useStoreBooking();
  return useMutation<Booking, ApiProblem, { amount: number; currency: string; note: string; idempotencyKey: string }>({
    mutationFn: ({ amount, currency, note, idempotencyKey }) =>
      call(() =>
        api.adminBookings.issueManualRefund({
          bookingReference: reference,
          idempotencyKey,
          manualRefundRequest: { amount: { amount, currency }, note },
        }),
      ),
    onSuccess: (booking) => {
      store(booking);
      void client.invalidateQueries({ queryKey: ['admin'] });
    },
  });
}
