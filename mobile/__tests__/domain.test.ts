import { call, newIdempotencyKey, toProblem } from '../src/api';
import { readConfig } from '../src/config';
import {
  formatDateTime,
  formatMoney,
  isEmail,
  isoDate,
  isTransient,
  parseIsoDate,
  pickSeats,
  validatePassenger,
  validateSearch,
} from '../src/domain';

const TODAY = new Date('2026-11-10T09:00:00Z');

describe('domain helpers', () => {
  it('formats money and UTC times', () => {
    expect(formatMoney({ amount: 420, currency: 'USD' })).toBe('USD 420.00');
    expect(formatMoney(undefined)).toBe('-');
    expect(formatDateTime(new Date('2026-12-01T08:05:00Z'))).toBe('2026-12-01 08:05 UTC');
  });

  it('handles ISO dates', () => {
    expect(isoDate(1, TODAY)).toBe('2026-11-11');
    expect(parseIsoDate('2026-02-30')).toBeUndefined();
    expect(parseIsoDate('next week')).toBeUndefined();
    expect(parseIsoDate('2026-12-01')?.toISOString()).toBe('2026-12-01T00:00:00.000Z');
  });

  it('validates the search form', () => {
    expect(validateSearch({ origin: 'DXB', destination: 'LHR', date: '2026-11-11', passengers: 2 }, TODAY)).toEqual({});
    expect(validateSearch({ origin: 'DX', destination: 'DX', date: '2026-11-01', passengers: 10 }, TODAY)).toEqual({
      origin: expect.any(String),
      destination: expect.any(String),
      date: 'Choose today or a later date',
      passengers: expect.any(String),
    });
    expect(validateSearch({ origin: 'DXB', destination: 'DXB', date: 'x', passengers: 1 }, TODAY)).toMatchObject({
      destination: 'Destination must differ from origin',
      date: 'Use the format YYYY-MM-DD',
    });
  });

  it('validates passengers and e-mail', () => {
    const ok = { firstName: 'Seán', lastName: "O'Brien", dateOfBirth: '1990-01-01' };
    expect(validatePassenger(ok, TODAY)).toBeUndefined();
    expect(validatePassenger({ ...ok, firstName: 'R2D2' }, TODAY)).toMatch(/first name/);
    expect(validatePassenger({ ...ok, lastName: '' }, TODAY)).toMatch(/last name/);
    expect(validatePassenger({ ...ok, dateOfBirth: '2030-01-01' }, TODAY)).toMatch(/date of birth/);
    expect(isEmail('a@b.co')).toBe(true);
    expect(isEmail('nope')).toBe(false);
  });

  it('seats a group together in one row when it can', () => {
    const seat = (seatNumber: string, available = true, cabinClass = 'ECONOMY') => ({ seatNumber, available, cabinClass });
    const seats = [seat('1A', true, 'BUSINESS'), seat('3A'), seat('3B', false), seat('3C'), seat('10A'), seat('10B'),
      seat('10C')];
    expect(pickSeats(seats, 'ECONOMY', 3)).toEqual(['10A', '10B', '10C']);
    expect(pickSeats(seats, 'ECONOMY', 4)).toEqual(['3A', '3C', '10A', '10B']);
    expect(pickSeats(seats, 'BUSINESS', 2)).toBeUndefined();
  });

  it('knows which states are still changing', () => {
    expect(isTransient('PAID')).toBe(true);
    expect(isTransient('CONFIRMED')).toBe(false);
  });
});

describe('api helpers', () => {
  it('reads config with defaults', () => {
    expect(readConfig(undefined).apiBaseUrl).toBe('http://10.0.2.2:8080');
    expect(readConfig({ apiBaseUrl: 'https://api.example/', oidcClientId: '' })).toMatchObject({
      apiBaseUrl: 'https://api.example',
      oidcClientId: 'skybook-mobile',
    });
  });

  it('turns failures into problems', async () => {
    const response = { status: 409, json: async () => ({ title: 'Conflict', code: 'SEAT_UNAVAILABLE' }) };
    expect(await toProblem({ response })).toEqual({
      status: 409, title: 'Conflict', code: 'SEAT_UNAVAILABLE', detail: 'Those seats were just taken. Please try again.',
    });
    const html = { status: 502, json: async () => { throw new Error('html'); } };
    expect(await toProblem({ response: html })).toMatchObject({ status: 502, title: 'Request failed' });
    expect(await toProblem(new TypeError('Network request failed'))).toMatchObject({ status: 0, title: 'No connection' });
    await expect(call(() => Promise.reject({ response }))).rejects.toMatchObject({ status: 409 });
    expect(newIdempotencyKey()).toMatch(/^m-[a-z0-9]+-[a-z0-9]+$/);
  });
});
