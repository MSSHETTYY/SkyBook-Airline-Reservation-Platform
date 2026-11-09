import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderApp } from '../test/render';

describe('authentication', () => {
  it('sends anonymous users to sign in and brings them back afterwards', async () => {
    const user = userEvent.setup();
    renderApp({ route: '/bookings' });

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/login?returnTo=%2Fbookings');
    await user.click(screen.getByRole('button', { name: 'Continue as customer' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'My bookings' })).toBeInTheDocument();
    expect(screen.getByText('Casey Customer')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'My bookings' })).toHaveAttribute('aria-current', 'page');
  });

  it('shows staff navigation by role and signs out', async () => {
    const user = userEvent.setup();
    renderApp({ role: 'ADMIN' });
    expect(screen.getByRole('link', { name: 'Servicing' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Flights' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(screen.queryByRole('link', { name: 'Servicing' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument();
  });

  it('ignores open-redirect attempts in returnTo', async () => {
    const user = userEvent.setup();
    renderApp({ route: '/login?returnTo=//evil.example' });
    await user.click(await screen.findByRole('button', { name: 'Continue as agent' }));
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/);
  });

  it('renders a not-found page with a skip link', async () => {
    renderApp({ route: '/nowhere' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Skip to main content' })).toHaveAttribute('href', '#main');
  });
});
