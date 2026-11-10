import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';
import { Alert } from 'react-native';

import BookScreen from '../app/book';
import BookingScreen from '../app/bookings/[reference]';
import MyBookingsScreen from '../app/bookings/index';
import SearchScreen from '../app/index';
import ResultsScreen from '../app/results';

const mockAuth = { ready: true, signedIn: true, signIn: jest.fn(), signOut: jest.fn(async () => undefined) };
jest.mock('../src/auth', () => ({
  useAuth: () => mockAuth,
  AuthProvider: ({ children }: { children: React.ReactNode }) => children,
}));

type Handler = (url: string, init: RequestInit) => { status: number; body?: unknown } | undefined;
let handlers: Handler[] = [];
const requests: Array<{ url: string; init: RequestInit }> = [];

function respond(status: number, body?: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    json: async () => body,
    text: async () => JSON.stringify(body),
    clone() { return this; },
  };
}

beforeEach(() => {
  handlers = [];
  requests.length = 0;
  mockAuth.signedIn = true;
  mockAuth.signIn.mockClear();
  globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = String(input);
    requests.push({ url, init });
    for (const handler of handlers) {
      const result = handler(url, init);
      if (result) return respond(result.status, result.body);
    }
    return respond(404, { title: 'Not found', status: 404, code: 'RESOURCE_NOT_FOUND' });
  }) as unknown as typeof fetch;
});

function TestLayout() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <Slot />
    </QueryClientProvider>
  );
}

const routes = {
  _layout: TestLayout,
  index: SearchScreen,
  results: ResultsScreen,
  book: BookScreen,
  'bookings/index': MyBookingsScreen,
  'bookings/[reference]': BookingScreen,
};

const OFFER = {
  flightId: 7, flightNumber: 'SB101', origin: 'DXB', destination: 'LHR', departureTime: '2026-12-01T08:00:00Z',
  arrivalTime: '2026-12-01T15:30:00Z', durationMinutes: 450, aircraftType: 'A320',
  cabins: [
    { cabinClass: 'ECONOMY', seatsAvailable: 10, fromPrice: { amount: 420, currency: 'USD' } },
    { cabinClass: 'BUSINESS', seatsAvailable: 0 },
  ],
};

function booking(status: string, extra: object = {}) {
  return {
    bookingReference: 'K7QX2M', status, contactEmail: 'ada@example.test', createdAt: '2026-11-10T09:00:00Z',
    flight: { flightId: 7, flightNumber: 'SB101', origin: 'DXB', destination: 'LHR', departureTime: '2026-12-01T08:00:00Z' },
    passengers: [{ firstName: 'Ada', lastName: 'Lovelace', seatNumber: '10A' }],
    totalPrice: { amount: 420, currency: 'USD' },
    amountRefunded: { amount: 0, currency: 'USD' },
    payment: { paymentId: 'pay_abcdefghijklmnopqrstuvwx', status: 'REQUIRES_PAYMENT' },
    ...extra,
  };
}

describe('mobile journey', () => {
  it('validates the search, then lists offers per cabin from the v2 API', async () => {
    handlers.push((url) => (url.includes('/api/v2/flights') ? { status: 200, body: { items: [OFFER], page: 0, size: 30,
      totalElements: 1 } } : undefined));
    await renderRouter(routes, { initialUrl: '/' });

    await fireEvent.changeText(screen.getByLabelText('From'), 'D1');
    await fireEvent.press(screen.getByRole('button', { name: 'Search flights' }));
    expect(await screen.findByText('Use a 3-letter airport code, e.g. DXB')).toBeTruthy();

    await fireEvent.changeText(screen.getByLabelText('From'), 'dxb');
    await fireEvent.press(screen.getByRole('button', { name: 'Search flights' }));

    expect(await screen.findByText(/SB101 · 2026-12-01 08:00 UTC/)).toBeTruthy();
    expect(screen.getByText('Economy: 10 left')).toBeTruthy();
    expect(screen.getByText('Not available')).toBeTruthy();
    const search = requests.find((r) => r.url.includes('/api/v2/flights'))!.url;
    expect(search).toContain('origin=DXB');
    expect(search).toContain('departureTo=');
  });

  it('asks anonymous users to sign in before booking', async () => {
    mockAuth.signedIn = false;
    handlers.push((url) => (url.includes('/api/v2/flights') ? { status: 200, body: { items: [OFFER], totalElements: 1,
      page: 0, size: 30 } } : undefined));
    await renderRouter(routes, { initialUrl: '/results?origin=DXB&destination=LHR&date=2026-12-01&passengers=1' });

    await fireEvent.press(await screen.findByRole('button', { name: 'Book from USD 420.00' }));
    expect(mockAuth.signIn).toHaveBeenCalled();
  });

  it('books with automatically assigned seats and one idempotency key', async () => {
    handlers.push((url, init) => {
      if (url.endsWith('/api/v1/flights/7/seats')) {
        return { status: 200, body: { flightId: 7, seats: [
          { seatNumber: '10A', cabinClass: 'ECONOMY', available: true, price: { amount: 420, currency: 'USD' } },
          { seatNumber: '10B', cabinClass: 'ECONOMY', available: false, price: { amount: 420, currency: 'USD' } },
        ] } };
      }
      if (url.endsWith('/api/v1/bookings') && init.method === 'POST') return { status: 201, body: booking('PENDING_PAYMENT') };
      if (url.endsWith('/api/v1/bookings/K7QX2M')) return { status: 200, body: booking('PENDING_PAYMENT') };
      return undefined;
    });
    await renderRouter(routes, { initialUrl: '/book?flightId=7&cabin=ECONOMY&passengers=1&flightNumber=SB101' });

    await fireEvent.press(screen.getByRole('button', { name: 'Continue to payment' }));
    expect(await screen.findByText('Enter a valid contact email')).toBeTruthy();

    await fireEvent.changeText(screen.getByLabelText('Contact email'), 'ada@example.test');
    await fireEvent.changeText(screen.getByLabelText('Passenger 1 first name'), 'Ada');
    await fireEvent.changeText(screen.getByLabelText('Passenger 1 last name'), 'Lovelace');
    await fireEvent.changeText(screen.getByLabelText('Passenger 1 date of birth (YYYY-MM-DD)'), '1990-12-10');
    await fireEvent.press(screen.getByRole('button', { name: 'Continue to payment' }));

    expect(await screen.findByText('Booking K7QX2M')).toBeTruthy();
    const create = requests.find((r) => r.init.method === 'POST')!;
    expect(JSON.parse(String(create.init.body))).toMatchObject({
      flightId: 7, passengers: [{ firstName: 'Ada', seatNumber: '10A', dateOfBirth: '1990-12-10' }],
    });
    expect(new Headers(create.init.headers).get('Idempotency-Key')).toMatch(/^m-/);
  });

  it('pays with the simulation and shows the declined path', async () => {
    let status = 'PENDING_PAYMENT';
    handlers.push((url, init) => {
      if (url.includes('/simulation')) {
        const outcome = JSON.parse(String(init.body)).outcome;
        if (outcome === 'SUCCEEDED') status = 'CONFIRMED';
        return { status: 202 };
      }
      if (url.endsWith('/api/v1/bookings/K7QX2M')) {
        return { status: 200, body: booking(status, status === 'CONFIRMED'
          ? { passengers: [{ firstName: 'Ada', lastName: 'Lovelace', seatNumber: '10A', ticketNumber: '1761234567890' }] }
          : {}) };
      }
      return undefined;
    });
    await renderRouter(routes, { initialUrl: '/bookings/K7QX2M' });

    await fireEvent.press(await screen.findByRole('button', { name: 'Simulate declined card' }));
    expect(await screen.findByText('The card was declined (simulated). Try again.')).toBeTruthy();

    await fireEvent.press(screen.getByRole('button', { name: 'Pay USD 420.00 (test)' }));
    expect(await screen.findByText('Confirmed')).toBeTruthy();
    expect(screen.getByText(/ticket 1761234567890/)).toBeTruthy();
  });

  it('cancels a confirmed booking after showing the refund quote', async () => {
    let status = 'CONFIRMED';
    handlers.push((url, init) => {
      if (url.endsWith('/cancellation-quote')) {
        return { status: 200, body: { bookingReference: 'K7QX2M', refundAmount: { amount: 420, currency: 'USD' },
          refundPercentage: 100, rule: 'At least 7 days before departure: full refund' } };
      }
      if (url.endsWith('/cancellation') && init.method === 'POST') {
        status = 'CANCELLED';
        return { status: 202, body: booking('CANCELLED') };
      }
      if (url.endsWith('/api/v1/bookings/K7QX2M')) return { status: 200, body: booking(status) };
      return undefined;
    });
    const alert = jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => {
      buttons?.find((b) => b.style === 'destructive')?.onPress?.();
    });
    await renderRouter(routes, { initialUrl: '/bookings/K7QX2M' });

    await fireEvent.press(await screen.findByRole('button', { name: 'Cancel booking' }));
    expect(await screen.findByText('Cancelled')).toBeTruthy();
    expect(alert).toHaveBeenCalledWith('Cancel K7QX2M?', expect.stringContaining('USD 420.00 (100%)'), expect.any(Array));
    alert.mockRestore();
  });

  it('lists my bookings, or asks to sign in', async () => {
    handlers.push((url) => (url.includes('/api/v1/bookings?') ? { status: 200, body: { items: [booking('CONFIRMED')],
      page: 0, size: 50, totalElements: 1 } } : undefined));
    await renderRouter(routes, { initialUrl: '/bookings' });
    expect(await screen.findByLabelText('Booking K7QX2M, Confirmed')).toBeTruthy();

    mockAuth.signedIn = false;
    await screen.unmount();
    await renderRouter(routes, { initialUrl: '/bookings' });
    expect(await screen.findByText('Sign in to see your bookings')).toBeTruthy();
  });

  it('shows problems from the API', async () => {
    handlers.push((url) => (url.includes('/api/v2/flights')
      ? { status: 503, body: { status: 503, title: 'Service unavailable', code: 'SERVICE_UNAVAILABLE' } } : undefined));
    await renderRouter(routes, { initialUrl: '/results?origin=DXB&destination=LHR&date=2026-12-01&passengers=1' });
    expect(await screen.findByText('SkyBook is temporarily unavailable. Please try again shortly.')).toBeTruthy();
  });
});
