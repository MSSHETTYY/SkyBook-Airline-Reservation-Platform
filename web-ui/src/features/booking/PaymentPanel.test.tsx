import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import type { Booking } from '../../api/types';
import { BookingStatus } from '../../api/types';
import { renderWithProviders } from '../../test/render';
import { amountDue, isStripeSecret, PaymentPanel } from './PaymentPanel';

const confirmPayment = vi.fn();

vi.mock('@stripe/stripe-js', () => ({ loadStripe: vi.fn(() => Promise.resolve({})) }));
vi.mock('@stripe/react-stripe-js', () => ({
  Elements: ({ children }: { children: React.ReactNode }) => <div data-testid="elements">{children}</div>,
  PaymentElement: () => <div>Card number</div>,
  useStripe: () => ({ confirmPayment }),
  useElements: () => ({}),
}));

const booking: Booking = {
  bookingReference: 'ABC234',
  status: BookingStatus.PENDING_PAYMENT,
  flight: { flightId: 1, flightNumber: 'SB101', origin: 'DXB', destination: 'LHR', departureTime: new Date() },
  passengers: [{ firstName: 'A', lastName: 'B', seatNumber: '5B' }],
  totalPrice: { amount: 420, currency: 'USD' },
  contactEmail: 'a@example.test',
  payment: { paymentId: 'pay_abcdefghijklmnopqrstuvwx', status: 'REQUIRES_PAYMENT',
    clientSecret: 'pi_3Abc_secret_xyz' },
  createdAt: new Date(),
};

describe('PaymentPanel', () => {
  it('uses Stripe Elements when a publishable key and a real client secret are present', async () => {
    const user = userEvent.setup();
    confirmPayment.mockResolvedValueOnce({ error: { message: 'Your card was declined.' } }).mockResolvedValueOnce({});
    renderWithProviders(<PaymentPanel booking={booking} stripePublishableKey="pk_test_123" />, { role: 'CUSTOMER' });

    expect(await screen.findByText('Card number')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Pay $420.00' }));
    expect(await screen.findByText('Your card was declined.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Pay $420.00' }));
    expect(await screen.findByText(/Payment received/)).toBeInTheDocument();
    expect(confirmPayment).toHaveBeenLastCalledWith(expect.objectContaining({ redirect: 'if_required' }));
  });

  it('falls back to simulation without a Stripe key, and waits for a payment to be attached', () => {
    renderWithProviders(<PaymentPanel booking={{ ...booking, payment: undefined }} stripePublishableKey="" />,
      { role: 'CUSTOMER' });
    expect(screen.getByText('Preparing your payment...')).toBeInTheDocument();
  });

  it('recognises Stripe secrets and the amount due for a date change', () => {
    expect(isStripeSecret('pi_123_secret_456')).toBe(true);
    expect(isStripeSecret('fake_secret_1')).toBe(false);
    expect(isStripeSecret(undefined)).toBe(false);
    expect(amountDue({ ...booking, status: BookingStatus.CHANGE_PENDING_PAYMENT,
      changeAmountDue: { amount: 80, currency: 'USD' } })).toEqual({ amount: 80, currency: 'USD' });
  });
});
