import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { db } from '../../mocks/server';
import { freeSeats } from '../../test/fixtures';
import { renderApp } from '../../test/render';
import { totalPrice } from './BookFlightPage';

async function fillPassenger(user: ReturnType<typeof userEvent.setup>, index: number, first: string, last: string) {
  const group = screen.getByRole('group', { name: new RegExp(`^Passenger ${index + 1}`) });
  await user.type(within(group).getByRole('textbox', { name: /First name/ }), first);
  await user.type(within(group).getByRole('textbox', { name: /Last name/ }), last);
  fireEvent.change(within(group).getByLabelText(/Date of birth/), { target: { value: '1985-04-12' } });
}

describe('BookFlightPage', () => {
  it('books two seats and lands on the booking page awaiting payment', async () => {
    const user = userEvent.setup();
    const [a, b] = freeSeats(db, 1, 2);
    renderApp({ route: '/flights/1/book?passengers=2', role: 'CUSTOMER' });

    expect(await screen.findByRole('heading', { level: 1, name: /SB101: DXB to LHR/ })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: /Contact email/ })).toHaveValue('customer@skybook.test');

    await user.click(screen.getByRole('button', { name: new RegExp(`^Seat ${a},`) }));
    await user.click(screen.getByRole('button', { name: new RegExp(`^Seat ${b},`) }));
    expect(screen.getByText('Total:').querySelector('strong')).toHaveTextContent('$840.00');
    await fillPassenger(user, 0, 'Ada', 'Lovelace');
    await fillPassenger(user, 1, 'Alan', 'Turing');
    expect(screen.getByRole('group', { name: `Passenger 1 - seat ${a}` })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Continue to payment' }));

    expect(await screen.findByRole('heading', { level: 1, name: /^Booking [A-Z2-9]{6}$/ })).toBeInTheDocument();
    expect(screen.getByText('Awaiting payment')).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: a })).toBeInTheDocument();
    expect(db.listBookings({}, 0, 10).items[0].passengers.map((p) => p.firstName)).toEqual(['Ada', 'Alan']);
  });

  it('requires one seat per passenger and valid passenger details', async () => {
    const user = userEvent.setup();
    renderApp({ route: '/flights/1/book?passengers=1', role: 'CUSTOMER' });
    await screen.findByRole('heading', { level: 1, name: /SB101/ });

    await user.click(screen.getByRole('button', { name: 'Continue to payment' }));
    expect(await screen.findByText('First name is required')).toBeInTheDocument();

    await fillPassenger(user, 0, 'R2', 'D2');
    await user.click(screen.getByRole('button', { name: 'Continue to payment' }));
    expect(await screen.findByText(/First name may only contain letters/)).toBeInTheDocument();

    await user.clear(screen.getByRole('textbox', { name: /First name/ }));
    await user.type(screen.getByRole('textbox', { name: /First name/ }), "Seán");
    await user.clear(screen.getByRole('textbox', { name: /Last name/ }));
    await user.type(screen.getByRole('textbox', { name: /Last name/ }), "O'Brien-Smith");
    await user.click(screen.getByRole('button', { name: 'Continue to payment' }));
    expect(await screen.findByText('Select 1 seat before continuing.')).toBeInTheDocument();
    expect(db.listBookings({}, 0, 10).totalElements).toBe(0);
  });

  it('recovers when a chosen seat is taken by someone else meanwhile', async () => {
    const user = userEvent.setup();
    renderApp({ route: '/flights/1/book?passengers=1', role: 'CUSTOMER' });
    await screen.findByRole('heading', { level: 1, name: /SB101/ });

    await user.click(screen.getByRole('button', { name: /^Seat 3A, .*available/ }));
    await fillPassenger(user, 0, 'Grace', 'Hopper');
    await user.click(screen.getByRole('button', { name: 'Continue to payment' }));

    expect(await screen.findByText(/One of the seats you picked was just taken/)).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /^Seat 3A, .*unavailable/ })).toBeInTheDocument();
    expect(screen.getByText(/0 of 1 seat selected/)).toBeInTheDocument();

    const [other] = freeSeats(db, 1, 1);
    await user.click(screen.getByRole('button', { name: new RegExp(`^Seat ${other},`) }));
    await user.click(screen.getByRole('button', { name: 'Continue to payment' }));
    expect(await screen.findByText('Awaiting payment')).toBeInTheDocument();
  });

  it('shows a problem when the flight does not exist', async () => {
    renderApp({ route: '/flights/9999/book', role: 'CUSTOMER' });
    expect(await screen.findByText('Resource not found')).toBeInTheDocument();
  });

  it('adds prices in cents', () => {
    const seat = (n: string, amount: number) => ({ seatNumber: n, cabinClass: 'ECONOMY' as never, available: true,
      price: { amount, currency: 'USD' } });
    expect(totalPrice([seat('1A', 0.1), seat('1B', 0.2)], ['1A', '1B'])).toEqual({ amount: 0.3, currency: 'USD' });
    expect(totalPrice([seat('1A', 1)], [])).toBeUndefined();
  });
});
