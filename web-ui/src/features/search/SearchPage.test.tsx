import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { toIsoDate } from '../../lib/format';
import { server } from '../../mocks/server';
import { renderApp } from '../../test/render';
import { parseSearchParams, tomorrow } from './searchSchema';

describe('SearchPage', () => {
  it('searches with the default route and lists bookable flights', async () => {
    const user = userEvent.setup();
    renderApp();
    expect(screen.getByRole('heading', { level: 1, name: 'Where would you like to go?' })).toHaveFocus();
    expect(document.title).toBe('Search flights | SkyBook');

    await user.click(screen.getByRole('button', { name: 'Search' }));

    const card = await screen.findByRole('article', { name: /SB101: DXB to LHR/ });
    expect(within(card).getByText('$420.00')).toBeInTheDocument();
    expect(screen.getByText('1 flight found')).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent(`origin=DXB&destination=LHR&date=${tomorrow()}`);

    await user.click(within(card).getByRole('link', { name: /Select flight SB101/ }));
    // Booking needs a signed-in user: the guard sends anonymous visitors to sign in first.
    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument();
  });

  it('validates the form before searching', async () => {
    const user = userEvent.setup();
    renderApp();
    await user.clear(screen.getByRole('combobox', { name: /From/ }));
    await user.clear(screen.getByRole('combobox', { name: /To/ }));
    await user.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findAllByText('Choose a city or airport from the list')).toHaveLength(2);
    expect(screen.getByRole('combobox', { name: /From/ })).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/);
  });

  it('suggests airports by city, old city name or code, and keeps the code for the search', async () => {
    const user = userEvent.setup();
    renderApp();
    const to = screen.getByRole('combobox', { name: /To/ });

    await user.clear(to);
    await user.type(to, 'dub');
    const options = await screen.findAllByRole('option');
    expect(options.map((o) => o.textContent)).toEqual(expect.arrayContaining([
      expect.stringContaining('Dubai International'), expect.stringContaining('Dublin Airport')]));
    await user.click(screen.getByRole('option', { name: /DUB Dublin/ }));
    expect(to).toHaveValue('Dublin (DUB)');

    await user.clear(to);
    await user.type(to, 'Bombay');
    await user.keyboard('{Enter}');
    expect(to).toHaveValue('Mumbai (BOM)');

    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByRole('heading', { name: 'Flights from Dubai (DXB) to Mumbai (BOM)' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('origin=DXB&destination=BOM');
    expect(await screen.findByText('Dubai International → Mumbai Chhatrapati Shivaji Maharaj International'))
      .toBeInTheDocument();
  });

  it('swaps origin and destination and rejects the same airport twice', async () => {
    const user = userEvent.setup();
    renderApp();
    await user.click(screen.getByRole('button', { name: 'Swap origin and destination' }));
    expect(screen.getByRole('combobox', { name: /From/ })).toHaveValue('London (LHR)');
    const to = screen.getByRole('combobox', { name: /To/ });
    await user.clear(to);
    await user.type(to, 'heathrow');
    await user.keyboard('{Enter}');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText('Destination must differ from the origin')).toBeInTheDocument();
  });

  it('offers popular routes as one-click searches', async () => {
    const user = userEvent.setup();
    renderApp();
    await user.click(screen.getByRole('button', { name: 'London (LHR) → New York (JFK)' }));
    expect(await screen.findByRole('article', { name: /SB301: LHR to JFK/ })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: /From/ })).toHaveValue('London (LHR)');
  });

  it('explains when nothing matches', async () => {
    const user = userEvent.setup();
    renderApp();
    const from = screen.getByRole('combobox', { name: /From/ });
    await user.clear(from);
    await user.type(from, 'atlantis');
    expect(await screen.findByText('No airport matches. Try a city name, e.g. Dubai')).toBeInTheDocument();
  });

  it('restores a search from the URL and shows an empty result', async () => {
    renderApp({ route: `/?origin=JFK&destination=DXB&date=${tomorrow()}&passengers=2` });
    expect(await screen.findByText('No flights found')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Flights from New York (JFK) to Dubai (DXB)' })).toBeInTheDocument();
  });

  it('shows server problems with a retry', async () => {
    server.use(http.get('*/api/v1/flights', () =>
      HttpResponse.json({ status: 503, title: 'Down', code: 'SERVICE_UNAVAILABLE', correlationId: 'cid-7' },
        { status: 503 })));
    renderApp({ route: `/?origin=DXB&destination=LHR&date=${tomorrow()}&passengers=1` });
    expect(await screen.findByText(/temporarily unavailable/, {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByText('Reference for support: cid-7')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('ignores invalid search parameters', () => {
    const today = new Date();
    expect(parseSearchParams(new URLSearchParams('origin=DXB&destination=LHR&date=2000-01-01'), today))
      .toBeUndefined();
    expect(parseSearchParams(new URLSearchParams(`origin=dxb&destination=lhr&date=${toIsoDate(today)}`), today))
      .toEqual({ origin: 'DXB', destination: 'LHR', date: toIsoDate(today), passengers: 1 });
  });
});
