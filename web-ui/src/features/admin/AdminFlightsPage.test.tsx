import { fireEvent, screen, waitForElementToBeRemoved, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { db } from '../../mocks/server';
import { renderApp } from '../../test/render';

function localDateTime(offsetDays: number, hour: number): string {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  d.setHours(hour, 0, 0, 0);
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:00`;
}

describe('AdminFlightsPage', () => {
  it('creates a flight after validating the form', async () => {
    const user = userEvent.setup();
    renderApp({ route: '/admin/flights', role: 'ADMIN' });
    expect(await screen.findByRole('table', { name: 'Flights' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'New flight' }));
    const dialog = await screen.findByRole('dialog', { name: 'New flight' });
    await user.click(within(dialog).getByRole('button', { name: 'Create flight' }));
    expect(await within(dialog).findByText(/Airline code plus 1-4 digits/)).toBeInTheDocument();

    await user.type(within(dialog).getByRole('textbox', { name: /Flight number/ }), 'sb901');
    await user.type(within(dialog).getByRole('textbox', { name: /^Origin/ }), 'DXB');
    await user.type(within(dialog).getByRole('textbox', { name: /^Destination/ }), 'DXB');
    fireEvent.change(within(dialog).getByLabelText(/Departure/), { target: { value: localDateTime(3, 10) } });
    fireEvent.change(within(dialog).getByLabelText(/Arrival/), { target: { value: localDateTime(3, 9) } });
    await user.click(within(dialog).getByRole('button', { name: 'Create flight' }));
    expect(await within(dialog).findByText('Must differ from origin')).toBeInTheDocument();
    expect(within(dialog).getByText('Arrival must be after departure')).toBeInTheDocument();

    await user.clear(within(dialog).getByRole('textbox', { name: /^Destination/ }));
    await user.type(within(dialog).getByRole('textbox', { name: /^Destination/ }), 'CDG');
    fireEvent.change(within(dialog).getByLabelText(/Arrival/), { target: { value: localDateTime(3, 17) } });
    await user.click(within(dialog).getByRole('button', { name: 'Create flight' }));

    await waitForElementToBeRemoved(() => screen.queryByRole('dialog'));
    expect(await screen.findByText('1–20 of 71')).toBeInTheDocument();
    const created = db.listFlights(0, 100).items.find((f) => f.flightNumber === 'SB901')!;
    expect(created).toMatchObject({ origin: 'DXB', destination: 'CDG', aircraftType: 'A320' });
    const seats = db.seatMap(created.flightId).seats;
    expect(seats).toHaveLength(180);
    expect(seats.filter((s) => s.cabinClass === 'BUSINESS')).toHaveLength(24);
  });

  it('cancels a flight after confirmation', async () => {
    const user = userEvent.setup();
    renderApp({ route: '/admin/flights', role: 'ADMIN' });
    const first = (await screen.findAllByRole('button', { name: /^Cancel flight SB101/ }))[0];
    await user.click(first);
    const dialog = await screen.findByRole('dialog', { name: 'Cancel flight SB101?' });
    await user.click(within(dialog).getByRole('button', { name: 'Cancel flight' }));
    expect(await screen.findByText('Cancelled')).toBeInTheDocument();
    expect(db.summary(1).status).toBe('CANCELLED');
  });

  it('is only for admins', async () => {
    renderApp({ route: '/admin/flights', role: 'AGENT' });
    expect(await screen.findByText('You do not have access to this page.')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Flights' })).not.toBeInTheDocument();
  });
});
