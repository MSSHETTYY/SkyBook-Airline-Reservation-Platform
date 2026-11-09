import { Alert, Box, Button, Paper, Stack, Typography } from '@mui/material';
import { useQueryClient } from '@tanstack/react-query';
import { lazy, Suspense, useState } from 'react';

import { queryKeys, useSimulatePayment } from '../../api/hooks';
import type { Booking } from '../../api/types';
import { BookingStatus } from '../../api/types';
import { HoldCountdown } from '../../components/HoldCountdown';
import { LoadingState } from '../../components/LoadingState';
import { ProblemAlert } from '../../components/ProblemAlert';
import { config } from '../../config';
import { formatMoney } from '../../lib/format';

// Stripe.js is only downloaded for users who actually pay by card.
const StripePaymentForm = lazy(() =>
  import('./StripePaymentForm').then((module) => ({ default: module.StripePaymentForm })),
);

interface Props {
  booking: Booking;
  stripePublishableKey?: string;
}

export function amountDue(booking: Booking) {
  return booking.status === BookingStatus.CHANGE_PENDING_PAYMENT ? booking.changeAmountDue : booking.totalPrice;
}

/** Real Stripe PaymentIntents have `pi_..._secret_...` client secrets; the fake provider's do not. */
export function isStripeSecret(secret: string | undefined): secret is string {
  return !!secret && /^pi_[A-Za-z0-9]+_secret_/.test(secret);
}

export function PaymentPanel({ booking, stripePublishableKey = config.stripePublishableKey }: Props) {
  const client = useQueryClient();
  const simulate = useSimulatePayment(booking.bookingReference);
  const [submitted, setSubmitted] = useState<'paid' | 'declined' | null>(null);
  const due = amountDue(booking);
  const payment = booking.payment;
  const amountLabel = formatMoney(due);

  const pay = (succeed: boolean) => {
    if (!payment) return;
    simulate.mutate(
      { paymentId: payment.paymentId, succeed },
      { onSuccess: () => setSubmitted(succeed ? 'paid' : 'declined') },
    );
  };

  return (
    <Paper component="section" aria-labelledby="payment-heading" sx={{ p: 3, mb: 3 }}>
      <Typography id="payment-heading" variant="h2" sx={{ mb: 1 }}>
        {booking.status === BookingStatus.CHANGE_PENDING_PAYMENT ? 'Pay the fare difference' : 'Payment'}
      </Typography>
      <Typography sx={{ mb: 1 }}>
        Amount due: <strong>{amountLabel}</strong>
      </Typography>
      {booking.holdExpiresAt && booking.status === BookingStatus.PENDING_PAYMENT && (
        <HoldCountdown expiresAt={booking.holdExpiresAt} />
      )}
      <Box sx={{ mt: 2 }}>
        {!payment ? (
          <LoadingState label="Preparing your payment..." />
        ) : submitted === 'paid' ? (
          <Alert severity="info">Payment received. Confirming your booking...</Alert>
        ) : stripePublishableKey && isStripeSecret(payment.clientSecret) ? (
          <Suspense fallback={<LoadingState label="Loading secure card form..." />}>
            <StripePaymentForm
              publishableKey={stripePublishableKey}
              clientSecret={payment.clientSecret}
              amountLabel={amountLabel}
              onSubmitted={() => {
                setSubmitted('paid');
                void client.invalidateQueries({ queryKey: queryKeys.booking(booking.bookingReference) });
              }}
            />
          </Suspense>
        ) : (
          <>
            <Alert severity="info" sx={{ mb: 2 }}>
              Test mode: no card is needed. Choose an outcome to simulate the payment provider.
            </Alert>
            {submitted === 'declined' && (
              <Alert severity="warning" sx={{ mb: 2 }}>
                The card was declined (simulated). Your seats are still held, so you can try again.
              </Alert>
            )}
            <ProblemAlert problem={simulate.error} />
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <Button variant="contained" onClick={() => pay(true)} disabled={simulate.isPending}>
                Pay {amountLabel} (test)
              </Button>
              <Button variant="outlined" onClick={() => pay(false)} disabled={simulate.isPending}>
                Simulate declined card
              </Button>
            </Stack>
          </>
        )}
      </Box>
    </Paper>
  );
}
