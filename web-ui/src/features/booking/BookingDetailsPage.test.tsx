import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { db } from '../../mocks/server';
import { confirmedBooking, createBooking, flightInDays, freeSeats } from '../../test/fixtures';
import { renderApp } from '../../test/render';

describe('BookingDetailsPage', () => {
  it('pays with the fake provider and follows the saga to a ticketed booking', async () => {
    const user = userEvent.setup();
    const booking = createBooking(db, 1);
    renderApp({ route: `/bookings/${booking.bookingReference}`, role: 'CUSTOMER' });

    expect(await screen.findByText('Awaiting payment')).toBeInTheDocument();
    expect(screen.getByText(/Seats held for/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Pay $420.00 (test)' }));

    expect(await screen.findByText('Confirmed', {}, { timeout: 6000 })).toBeInTheDocument();
    expect(screen.getByText(/Your booking is confirmed/)).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: /^176\d{10}$/ })).toBeInTheDocument();
    expect(screen.getByText('Booking status: Confirmed')).toBeInTheDocument();
  });

  it('keeps the seats held after a declined card so the customer can retry', async () => {
    const user = userEvent.setup();
    const booking = createBooking(db, 1);
    renderApp({ route: `/bookings/${booking.bookingReference}`, role: 'CUSTOMER' });

    await user.click(await screen.findByRole('button', { name: 'Simulate declined card' }));
    expect(await screen.findByText(/The card was declined \(simulated\)/)).toBeInTheDocument();
    expect(screen.getByText('Awaiting payment')).toBeInTheDocument();
  });

  it('cancels after showing the refund quote', async () => {
    const user = userEvent.setup();
    const booking = confirmedBooking(db, flightInDays(db, 8).flightId);
    renderApp({ route: `/bookings/${booking.bookingReference}`, role: 'CUSTOMER' });

    await user.click(await screen.findByRole('button', { name: 'Cancel booking' }));
    const dialog = await screen.findByRole('dialog', { name: `Cancel booking ${booking.bookingReference}?` });
    expect(await within(dialog).findByText(/100% of the fare/)).toBeInTheDocument();
    expect(within(dialog).getByText(/At least 7 days before departure/)).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Cancel booking' }));
    expect(await screen.findByText('This booking has been cancelled.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Change date' })).not.toBeInTheDocument();
  });

  it('can back out of a cancellation', async () => {
    const user = userEvent.setup();
    const booking = confirmedBooking(db, flightInDays(db, 3).flightId);
    renderApp({ route: `/bookings/${booking.bookingReference}`, role: 'CUSTOMER' });

    await user.click(await screen.findByRole('button', { name: 'Cancel booking' }));
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText(/50% of the fare/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Keep booking' }));
    expect(screen.getByText('Confirmed')).toBeInTheDocument();
  });

  it('hides other customers\' bookings', async () => {
    const [seat] = freeSeats(db, 1, 1);
    const theirs = db.createBooking('Bearer mock.AGENT', { flightId: 1, contactEmail: 'x@example.test',
      passengers: [{ firstName: 'Other', lastName: 'Person', dateOfBirth: '1990-01-01', seatNumber: seat }] });
    renderApp({ route: `/bookings/${theirs.bookingReference}`, role: 'CUSTOMER' });
    expect(await screen.findByText('Resource not found')).toBeInTheDocument();
  });
});
