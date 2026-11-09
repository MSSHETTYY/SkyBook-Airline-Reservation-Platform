export interface MoneyLike {
  amount: number;
  currency: string;
}

const LOCALE = 'en-US';

export function formatMoney(money: MoneyLike | undefined | null): string {
  if (!money) return '-';
  return new Intl.NumberFormat(LOCALE, { style: 'currency', currency: money.currency }).format(money.amount);
}

export function formatDateTime(value: Date | undefined | null): string {
  if (!value) return '-';
  return new Intl.DateTimeFormat(LOCALE, { dateStyle: 'medium', timeStyle: 'short' }).format(value);
}

export function formatTime(value: Date): string {
  return new Intl.DateTimeFormat(LOCALE, { timeStyle: 'short' }).format(value);
}

export function formatDuration(from: Date, to: Date): string {
  const minutes = Math.max(0, Math.round((to.getTime() - from.getTime()) / 60_000));
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h > 0 ? `${h}h ${m.toString().padStart(2, '0')}m` : `${m}m`;
}

/** `YYYY-MM-DD` in local time: what a date input holds, and what the API's `format: date` expects. */
export function toIsoDate(value: Date): string {
  const y = value.getFullYear().toString().padStart(4, '0');
  const m = (value.getMonth() + 1).toString().padStart(2, '0');
  const d = value.getDate().toString().padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** Parses `YYYY-MM-DD` as local midnight (`new Date('2026-01-02')` would be UTC midnight). */
export function fromIsoDate(value: string): Date {
  const [y, m, d] = value.split('-').map(Number);
  const date = new Date(0);
  date.setFullYear(y, m - 1, d);
  date.setHours(0, 0, 0, 0);
  return date;
}
