import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { db } from '../../mocks/server';
import { confirmedBooking, createBooking, freeSeats } from '../../test/fixtures';
import { renderApp } from '../../test/render';

describe('MyBookingsPage', () => {
  it('shows an empty state with a way to start booking', async () => {
    renderApp({ route: '/bookings', role: 'CUSTOMER' });
    expect(await screen.findByText('You have no bookings yet')).toBeInTheDocument();
    expect(within(screen.getByRole('main')).getByRole('link', { name: 'Search flights' })).toHaveAttribute('href', '/');
  });

  it('lists only the caller\'s bookings, newest first, and pages through them', async () => {
    const user = userEvent.setup();
    const first = confirmedBooking(db, 1);
    for (let i = 0; i < 10; i++) createBooking(db, 2 + i);
    db.createBooking('Bearer mock.AGENT', {
      flightId: 3, contactEmail: 'x@example.test',
      passengers: [
        { firstName: 'Other', lastName: 'Person', dateOfBirth: '1990-01-01', seatNumber: freeSeats(db, 3, 1)[0] },
      ],
    });
    renderApp({ route: '/bookings', role: 'CUSTOMER' });

    expect(await screen.findByRole('table', { name: 'My bookings' })).toBeInTheDocument();
    expect(screen.getByText('1–10 of 11')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Go to next page' }));
    const link = await screen.findByRole('link', { name: first.bookingReference });
    expect(link).toHaveAttribute('href', `/bookings/${first.bookingReference}`);
    expect(screen.getByText('Confirmed')).toBeInTheDocument();
  });
});
