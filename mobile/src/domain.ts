/** Pure helpers shared by the screens (unit-tested without rendering). */

export interface MoneyLike {
  amount: number;
  currency: string;
}

export function formatMoney(money: MoneyLike | undefined | null): string {
  if (!money) return '-';
  return `${money.currency} ${money.amount.toFixed(2)}`;
}

export function formatDateTime(value: Date): string {
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${value.getUTCFullYear()}-${pad(value.getUTCMonth() + 1)}-${pad(value.getUTCDate())} `
    + `${pad(value.getUTCHours())}:${pad(value.getUTCMinutes())} UTC`;
}

export function isoDate(daysFromToday: number, today: Date = new Date()): string {
  const d = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate() + daysFromToday));
  return d.toISOString().slice(0, 10);
}

/** `YYYY-MM-DD` to a Date at UTC midnight, or undefined if invalid. */
export function parseIsoDate(value: string): Date | undefined {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return undefined;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== value ? undefined : date;
}

export interface SearchInput {
  origin: string;
  destination: string;
  date: string;
  passengers: number;
}

export function validateSearch(input: SearchInput, today: Date = new Date()): Partial<Record<keyof SearchInput, string>> {
  const errors: Partial<Record<keyof SearchInput, string>> = {};
  if (!/^[A-Z]{3}$/.test(input.origin)) errors.origin = 'Use a 3-letter airport code, e.g. DXB';
  if (!/^[A-Z]{3}$/.test(input.destination)) errors.destination = 'Use a 3-letter airport code, e.g. LHR';
  else if (input.destination === input.origin) errors.destination = 'Destination must differ from origin';
  const date = parseIsoDate(input.date);
  if (!date) errors.date = 'Use the format YYYY-MM-DD';
  else if (input.date < isoDate(0, today)) errors.date = 'Choose today or a later date';
  if (!Number.isInteger(input.passengers) || input.passengers < 1 || input.passengers > 9) {
    errors.passengers = 'Between 1 and 9 passengers';
  }
  return errors;
}

export interface PassengerInput {
  firstName: string;
  lastName: string;
  dateOfBirth: string;
}

export function validatePassenger(p: PassengerInput, today: Date = new Date()): string | undefined {
  const name = /^[\p{L}][\p{L} '.-]{0,49}$/u;
  if (!name.test(p.firstName.trim())) return 'Enter the first name as on the passport';
  if (!name.test(p.lastName.trim())) return 'Enter the last name as on the passport';
  const dob = parseIsoDate(p.dateOfBirth.trim());
  if (!dob || p.dateOfBirth.trim() >= isoDate(0, today) || p.dateOfBirth.trim() < '1900-01-01') {
    return 'Enter the date of birth as YYYY-MM-DD';
  }
  return undefined;
}

export function isEmail(value: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.trim());
}

interface SeatLike {
  seatNumber: string;
  cabinClass: string;
  available: boolean;
}

/**
 * Mobile books without a seat map: the cheapest way to seat a group together is consecutive free seats
 * in one row of the chosen cabin, falling back to the first free seats in seat order.
 */
export function pickSeats(seats: SeatLike[], cabin: string, count: number): string[] | undefined {
  const free = seats.filter((s) => s.available && s.cabinClass === cabin);
  if (free.length < count) return undefined;
  const rows = new Map<number, string[]>();
  for (const seat of free) {
    const row = Number.parseInt(seat.seatNumber, 10);
    rows.set(row, [...(rows.get(row) ?? []), seat.seatNumber]);
  }
  const sortedRows = [...rows.keys()].sort((a, b) => a - b);
  for (const row of sortedRows) {
    const inRow = rows.get(row)!.sort();
    if (inRow.length >= count) return inRow.slice(0, count);
  }
  return sortedRows.flatMap((row) => rows.get(row)!.sort()).slice(0, count);
}

const TRANSIENT = new Set(['PENDING_PAYMENT', 'PAID', 'CHANGE_PENDING_PAYMENT', 'CANCELLATION_PENDING']);

export function isTransient(status: string): boolean {
  return TRANSIENT.has(status);
}

export const STATUS_LABELS: Record<string, string> = {
  PENDING_PAYMENT: 'Awaiting payment',
  PAID: 'Issuing tickets',
  CONFIRMED: 'Confirmed',
  CHANGE_PENDING_PAYMENT: 'Change awaiting payment',
  CANCELLATION_PENDING: 'Cancellation in progress',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
  FAILED: 'Failed - refunded',
};
