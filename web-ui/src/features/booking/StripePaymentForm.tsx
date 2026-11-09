import { Alert, Box, Button } from '@mui/material';
import { Elements, PaymentElement, useElements, useStripe } from '@stripe/react-stripe-js';
import type { Stripe } from '@stripe/stripe-js';
import { loadStripe } from '@stripe/stripe-js';
import type { FormEvent } from 'react';
import { useState } from 'react';

const stripeByKey = new Map<string, Promise<Stripe | null>>();

/** loadStripe must run once per key, outside render, or Elements would re-mount on every render. */
function stripeFor(publishableKey: string): Promise<Stripe | null> {
  let stripe = stripeByKey.get(publishableKey);
  if (!stripe) {
    stripe = loadStripe(publishableKey);
    stripeByKey.set(publishableKey, stripe);
  }
  return stripe;
}

interface Props {
  publishableKey: string;
  clientSecret: string;
  amountLabel: string;
  onSubmitted: () => void;
}

/**
 * Card details go from Stripe's iframe straight to Stripe, never through SkyBook (PCI SAQ A).
 * The booking is confirmed by the webhook, not by this page: onSubmitted only starts polling.
 */
export function StripePaymentForm({ publishableKey, clientSecret, amountLabel, onSubmitted }: Props) {
  return (
    <Elements stripe={stripeFor(publishableKey)} options={{ clientSecret }}>
      <CardForm amountLabel={amountLabel} onSubmitted={onSubmitted} />
    </Elements>
  );
}

function CardForm({ amountLabel, onSubmitted }: { amountLabel: string; onSubmitted: () => void }) {
  const stripe = useStripe();
  const elements = useElements();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!stripe || !elements) return;
    setBusy(true);
    setError(null);
    const result = await stripe.confirmPayment({
      elements,
      redirect: 'if_required',
      confirmParams: { return_url: window.location.href },
    });
    setBusy(false);
    if (result.error) {
      setError(result.error.message ?? 'The payment could not be completed.');
    } else {
      onSubmitted();
    }
  };

  return (
    <Box component="form" onSubmit={submit} aria-label="Card payment">
      <PaymentElement />
      {error && (
        <Alert severity="error" sx={{ mt: 2 }}>
          {error}
        </Alert>
      )}
      <Button type="submit" variant="contained" sx={{ mt: 2 }} disabled={!stripe || busy}>
        {busy ? 'Processing...' : `Pay ${amountLabel}`}
      </Button>
    </Box>
  );
}
