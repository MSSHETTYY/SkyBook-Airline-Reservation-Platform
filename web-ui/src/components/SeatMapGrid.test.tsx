import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';

import type { Seat } from '../api/types';
import { CabinClass } from '../api/types';
import { SeatMapGrid } from './SeatMapGrid';

const seat = (seatNumber: string, available = true, cabinClass = CabinClass.ECONOMY): Seat => ({
  seatNumber,
  cabinClass,
  available,
  price: { amount: cabinClass === CabinClass.BUSINESS ? 250 : 100, currency: 'USD' },
});

const SEATS = [seat('1A', true, CabinClass.BUSINESS), seat('1B', true, CabinClass.BUSINESS), seat('2A'),
  seat('2B', false), seat('2C'), seat('10A')];

function Harness({ max }: { max: number }) {
  const [selected, setSelected] = useState<string[]>([]);
  return <SeatMapGrid seats={SEATS} selected={selected} maxSelectable={max} onChange={setSelected} />;
}

describe('SeatMapGrid', () => {
  it('labels every seat with number, cabin, price and state', () => {
    render(<Harness max={1} />);
    expect(screen.getByRole('button', { name: 'Seat 1A, business, $250.00, available' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Seat 2B, economy, $100.00, unavailable' }))
      .toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('group', { name: 'Seat map' })).toBeInTheDocument();
  });

  it('orders rows numerically, not alphabetically', () => {
    render(<Harness max={1} />);
    const names = screen.getAllByRole('button').map((b) => b.getAttribute('aria-label')!.split(',')[0]);
    expect(names.indexOf('Seat 2A')).toBeLessThan(names.indexOf('Seat 10A'));
  });

  it('replaces the choice when only one seat may be selected, and ignores unavailable seats', async () => {
    const user = userEvent.setup();
    render(<Harness max={1} />);
    await user.click(screen.getByRole('button', { name: /^Seat 2A/ }));
    await user.click(screen.getByRole('button', { name: /^Seat 2C/ }));
    await user.click(screen.getByRole('button', { name: /^Seat 2B/ }));
    expect(screen.getByRole('button', { name: /^Seat 2C.*selected/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /^Seat 2A.*available/ })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByText(/1 of 1 seat selected: 2C/)).toBeInTheDocument();
  });

  it('works from the keyboard and stops at the passenger count', async () => {
    const user = userEvent.setup();
    render(<Harness max={2} />);
    screen.getByRole('button', { name: /^Seat 1A/ }).focus();
    await user.keyboard('{Enter}');
    await user.tab();
    await user.keyboard(' ');
    expect(screen.getByText(/2 of 2 seats selected: 1A, 1B/)).toBeInTheDocument();
    expect(screen.getByText(/Deselect a seat/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /^Seat 2A/ }));
    expect(screen.getByRole('button', { name: /^Seat 2A/ })).toHaveAttribute('aria-pressed', 'false');

    await user.click(screen.getByRole('button', { name: /^Seat 1A/ }));
    expect(screen.getByText(/1 of 2 seats selected: 1B/)).toBeInTheDocument();
  });
});
