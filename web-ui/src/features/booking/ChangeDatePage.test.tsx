import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { toIsoDate } from '../../lib/format';
import { db } from '../../mocks/server';
import { confirmedBooking, createBooking, flightInDays } from '../../test/fixtures';
import { renderApp } from '../../test/render';

describe('ChangeDatePage', () => {
  it('moves a confirmed booking to a later flight and asks for the fare difference', async () => {
    const user = userEvent.setup();
    const booking = confirmedBooking(db, flightInDays(db, 2).flightId);
    const target = flightInDays(db, 5);
    renderApp({ route: `/bookings/${booking.bookingReference}/change`, role: 'CUSTOMER' });

    const date = await screen.findByLabelText(/New departure date/);
    fireEvent.change(date, { target: { value: toIsoDate(new Date(target.departureTime)) } });

    const card = await screen.findByRole('article', { name: /SB101: DXB to LHR/ });
    await user.click(within(card).getByRole('button', { name: 'Choose SB101' }));
    expect(within(card).getByRole('button', { name: 'Chosen' })).toHaveAttribute('aria-pressed', 'true');

    const business = db.seatMap(target.flightId).seats.find((s) => s.available && s.cabinClass === 'BUSINESS')!;
    await user.click(await screen.findByRole('button', { name: new RegExp(`^Seat ${business.seatNumber},`) }));
    await user.click(screen.getByRole('button', { name: 'Confirm change' }));

    expect(await screen.findByText('Change awaiting payment')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Pay the fare difference' })).toBeInTheDocument();
    // 2.5 x 420 business seat - 420 paid + 50 change fee
    expect(screen.getByText('$680.00')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Pay $680.00 (test)' }));
    expect(await screen.findByText('Confirmed', {}, { timeout: 6000 })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: business.seatNumber })).toBeInTheDocument();
  });

  it('only allows confirmed bookings to be changed', async () => {
    const booking = createBooking(db, 1);
    renderApp({ route: `/bookings/${booking.bookingReference}/change`, role: 'CUSTOMER' });
    expect(await screen.findByText('Only confirmed bookings can be changed.')).toBeInTheDocument();
  });
});
