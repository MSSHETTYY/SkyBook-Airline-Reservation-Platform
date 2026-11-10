import { zodResolver } from '@hookform/resolvers/zod';
import CloseIcon from '@mui/icons-material/Close';
import { Alert, Box, Button, Divider, Drawer, IconButton, List, ListItem, ListItemText, Stack, TextField,
  Typography } from '@mui/material';
import { useId, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { z } from 'zod';

import { useBooking, useManualRefund, usePaymentsForBooking } from '../../api/hooks';
import { useIdempotencyKey } from '../../api/idempotency';
import type { Booking } from '../../api/types';
import { hasAnyRole, useSession } from '../../auth/session';
import { LoadingState } from '../../components/LoadingState';
import { ProblemAlert } from '../../components/ProblemAlert';
import { StatusChip } from '../../components/StatusChip';
import { formatDateTime, formatMoney } from '../../lib/format';

interface Props {
  reference: string | undefined;
  onClose: () => void;
}

/** Side panel for agents: booking facts, its payment history and (admins only) goodwill refunds. */
export function BookingServicingDrawer({ reference, onClose }: Props) {
  const titleId = useId();
  return (
    <Drawer anchor="right" open={reference !== undefined} onClose={onClose}
      slotProps={{ paper: { role: 'dialog', 'aria-modal': true, 'aria-labelledby': titleId, sx: { width: { xs: '100%', sm: 460 }, p: 3 } } }}>
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Typography id={titleId} variant="h2" component="h2">
          Booking {reference}
        </Typography>
        <IconButton onClick={onClose} aria-label="Close booking panel">
          <CloseIcon />
        </IconButton>
      </Stack>
      {reference && <ServicingContent reference={reference} />}
    </Drawer>
  );
}

function ServicingContent({ reference }: { reference: string }) {
  const booking = useBooking(reference);
  const payments = usePaymentsForBooking(reference);
  const { user } = useSession();

  if (booking.isLoading) return <LoadingState />;
  if (booking.error) return <ProblemAlert problem={booking.error} />;
  if (!booking.data) return null;
  const b = booking.data;

  return (
    <Box sx={{ mt: 2 }}>
      <StatusChip status={b.status} />
      <Typography sx={{ mt: 2 }}>
        {b.flight.flightNumber} {b.flight.origin}-{b.flight.destination}, {formatDateTime(b.flight.departureTime)}
      </Typography>
      <Typography>Contact: {b.contactEmail}</Typography>
      <Typography>
        Paid {formatMoney(b.amountPaid)}, refunded {formatMoney(b.amountRefunded)}
      </Typography>

      <Divider sx={{ my: 2 }} />
      <Typography variant="h3" component="h3">
        Payments
      </Typography>
      {payments.isLoading && <LoadingState label="Loading payments..." />}
      <ProblemAlert problem={payments.error} />
      {payments.data && payments.data.length === 0 && <Typography>No payments yet.</Typography>}
      <List dense aria-label="Payments">
        {payments.data?.map((p) => (
          <ListItem key={p.paymentId} disableGutters>
            <ListItemText
              primary={`${formatMoney(p.amount)} - ${p.purpose} - ${p.status}`}
              secondary={`${p.paymentId} · ${formatDateTime(p.createdAt)}${
                p.refunds?.length ? ` · refunds: ${p.refunds.map((r) => `${formatMoney(r.amount)} ${r.status}`)
                  .join(', ')}` : ''}${p.failureReason ? ` · last failure: ${p.failureReason}` : ''}`}
            />
          </ListItem>
        ))}
      </List>

      {hasAnyRole(user, ['ADMIN']) && (
        <>
          <Divider sx={{ my: 2 }} />
          <ManualRefundForm booking={b} />
        </>
      )}
    </Box>
  );
}

function refundableCents(b: Booking): number {
  return Math.round((b.amountPaid?.amount ?? 0) * 100) - Math.round((b.amountRefunded?.amount ?? 0) * 100);
}

function ManualRefundForm({ booking }: { booking: Booking }) {
  const refund = useManualRefund(booking.bookingReference);
  const keyFor = useIdempotencyKey();
  const [done, setDone] = useState(false);
  const max = refundableCents(booking) / 100;
  const currency = booking.totalPrice.currency;
  const schema = z.object({
    amount: z
      .string()
      .trim()
      .min(1, 'Enter an amount')
      .pipe(
        z.coerce
          .number<string>({ error: 'Enter a number' })
          .positive('Must be greater than 0')
          .max(max, `At most ${formatMoney({ amount: max, currency })}`),
      ),
    note: z.string().trim().min(3, 'Explain the refund (3-200 characters)').max(200),
  });
  type Input = z.input<typeof schema>;
  type Values = z.output<typeof schema>;
  const { control, handleSubmit, reset } = useForm<Input, unknown, Values>({
    resolver: zodResolver(schema),
    defaultValues: { amount: '', note: '' },
  });

  if (max <= 0) return <Typography>Nothing left to refund on this booking.</Typography>;

  const onSubmit = (values: Values) =>
    refund.mutate(
      { amount: values.amount, currency, note: values.note, idempotencyKey: keyFor(values) },
      {
        onSuccess: () => {
          setDone(true);
          reset({ amount: '', note: '' });
        },
      },
    );

  return (
    <Box component="form" noValidate onSubmit={handleSubmit(onSubmit)} aria-labelledby="refund-heading">
      <Typography id="refund-heading" variant="h3" component="h3" sx={{ mb: 2 }}>
        Goodwill refund
      </Typography>
      {done && (
        <Alert severity="success" sx={{ mb: 2 }}>
          Refund requested. It appears here once the payment provider confirms it.
        </Alert>
      )}
      <Stack spacing={2}>
        <Controller
          name="amount"
          control={control}
          render={({ field, fieldState }) => (
            <TextField
              {...field}
              value={field.value ?? ''}
              type="number"
              label={`Amount (${currency})`}
              required
              error={!!fieldState.error}
              helperText={fieldState.error?.message ?? `Up to ${formatMoney({ amount: max, currency })}`}
            />
          )}
        />
        <Controller
          name="note"
          control={control}
          render={({ field, fieldState }) => (
            <TextField
              {...field}
              label="Reason"
              required
              multiline
              minRows={2}
              error={!!fieldState.error}
              helperText={fieldState.error?.message ?? 'Recorded in the audit trail'}
            />
          )}
        />
        <ProblemAlert problem={refund.error} />
        <Button type="submit" variant="contained" disabled={refund.isPending}>
          {refund.isPending ? 'Requesting...' : 'Issue refund'}
        </Button>
      </Stack>
    </Box>
  );
}
