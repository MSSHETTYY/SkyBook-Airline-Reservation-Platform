import { formatDateTime, formatDuration, formatMoney, fromIsoDate, toIsoDate } from './format';

describe('format', () => {
  it('formats money with the currency of the amount', () => {
    expect(formatMoney({ amount: 1234.5, currency: 'USD' })).toBe('$1,234.50');
    expect(formatMoney({ amount: 10, currency: 'EUR' })).toBe('€10.00');
    expect(formatMoney(undefined)).toBe('-');
  });

  it('formats durations in hours and minutes', () => {
    const from = new Date('2026-01-01T08:00:00Z');
    expect(formatDuration(from, new Date('2026-01-01T15:30:00Z'))).toBe('7h 30m');
    expect(formatDuration(from, new Date('2026-01-01T08:45:00Z'))).toBe('45m');
    expect(formatDuration(from, new Date('2026-01-01T07:00:00Z'))).toBe('0m');
  });

  it('round-trips local calendar dates without a timezone shift', () => {
    const date = fromIsoDate('2026-03-09');
    expect(date.getFullYear()).toBe(2026);
    expect(date.getMonth()).toBe(2);
    expect(date.getDate()).toBe(9);
    expect(toIsoDate(date)).toBe('2026-03-09');
    expect(toIsoDate(fromIsoDate('0099-12-31'))).toBe('0099-12-31');
  });

  it('shows a dash for missing date-times', () => {
    expect(formatDateTime(undefined)).toBe('-');
    expect(formatDateTime(new Date('2026-01-01T08:00:00Z'))).toMatch(/2026/);
  });
});
