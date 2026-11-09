import { screen, waitForElementToBeRemoved, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { db } from '../../mocks/server';
import { confirmedBooking, createBooking } from '../../test/fixtures';
import { renderApp } from '../../test/render';

describe('AdminBookingsPage', () => {
  it('filters bookings and issues a goodwill refund as admin', async () => {
    const user = userEvent.setup();
    const paid = confirmedBooking(db, 1);
    createBooking(db, 2);
    renderApp({ route: '/admin/bookings', role: 'ADMIN' });

    const table = await screen.findByRole('table', { name: 'Bookings' });
    expect(within(table).getAllByRole('row')).toHaveLength(3);

    await user.click(screen.getByRole('combobox', { name: 'Status' }));
    await user.click(await screen.findByRole('option', { name: 'Confirmed' }));
    expect(await screen.findByText('1–1 of 1')).toBeInTheDocument();

    await user.type(screen.getByRole('textbox', { name: 'Flight ID' }), '2x{Enter}');
    expect(screen.getByRole('textbox', { name: 'Flight ID' })).toHaveValue('2');
    expect(await screen.findByText('No bookings match these filters')).toBeInTheDocument();
    await user.clear(screen.getByRole('textbox', { name: 'Flight ID' }));
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    await user.click(await screen.findByRole('button', { name: `Open booking ${paid.bookingReference}` }));
    const drawer = await screen.findByRole('dialog', { name: `Booking ${paid.bookingReference}` });
    expect(await within(drawer).findByText(/\$420\.00 - BOOKING - SUCCEEDED/)).toBeInTheDocument();

    await user.type(within(drawer).getByRole('spinbutton', { name: /Amount/ }), '500');
    await user.type(within(drawer).getByRole('textbox', { name: /Reason/ }), 'Delayed flight');
    await user.click(within(drawer).getByRole('button', { name: 'Issue refund' }));
    expect(await within(drawer).findByText('At most $420.00')).toBeInTheDocument();

    await user.clear(within(drawer).getByRole('spinbutton', { name: /Amount/ }));
    await user.type(within(drawer).getByRole('spinbutton', { name: /Amount/ }), '20');
    await user.click(within(drawer).getByRole('button', { name: 'Issue refund' }));
    expect(await within(drawer).findByText(/Refund requested/)).toBeInTheDocument();
    expect(await within(drawer).findByText(/refunds: \$20\.00 SUCCEEDED/)).toBeInTheDocument();

    await user.click(within(drawer).getByRole('button', { name: 'Close booking panel' }));
    await waitForElementToBeRemoved(() => screen.queryByRole('dialog'));
  });

  it('lets agents service bookings without refund rights', async () => {
    const user = userEvent.setup();
    const booking = createBooking(db, 1);
    renderApp({ route: '/admin/bookings', role: 'AGENT' });
    await user.click(await screen.findByRole('button', { name: `Open booking ${booking.bookingReference}` }));
    const drawer = await screen.findByRole('dialog');
    expect(await within(drawer).findByText(/REQUIRES_PAYMENT/)).toBeInTheDocument();
    expect(within(drawer).queryByRole('button', { name: 'Issue refund' })).not.toBeInTheDocument();
  });

  it('keeps customers out', async () => {
    renderApp({ route: '/admin/bookings', role: 'CUSTOMER' });
    expect(await screen.findByText('You do not have access to this page.')).toBeInTheDocument();
  });
});
