import { act, render, screen } from '@testing-library/react';

import { HoldCountdown } from './HoldCountdown';

describe('HoldCountdown', () => {
  afterEach(() => vi.useRealTimers());

  it('ticks every second but announces minutes only', () => {
    vi.useFakeTimers();
    const start = Date.parse('2026-01-01T10:00:00Z');
    let now = start;
    render(<HoldCountdown expiresAt={new Date(start + 125_000)} now={() => now} />);
    expect(screen.getByText('2:05')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('2 minutes left to pay');

    act(() => {
      now += 70_000;
      vi.advanceTimersByTime(1000);
    });
    expect(screen.getByText('0:55')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Less than a minute left to pay');

    act(() => {
      now += 60_000;
      vi.advanceTimersByTime(1000);
    });
    expect(screen.getByRole('status')).toHaveTextContent('Your seat hold has expired.');
  });

  it('uses the singular for one minute', () => {
    const now = Date.now();
    render(<HoldCountdown expiresAt={new Date(now + 61_000)} now={() => now} />);
    expect(screen.getByRole('status')).toHaveTextContent('1 minute left to pay');
  });
});
