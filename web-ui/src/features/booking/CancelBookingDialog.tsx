import { Typography } from '@mui/material';

import { useCancelBooking, useCancellationQuote } from '../../api/hooks';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { LoadingState } from '../../components/LoadingState';
import { ProblemAlert } from '../../components/ProblemAlert';
import { formatMoney } from '../../lib/format';

interface Props {
  reference: string;
  open: boolean;
  onClose: () => void;
}

/** Shows the refund the fare rules allow right now, before the customer commits to cancelling. */
export function CancelBookingDialog({ reference, open, onClose }: Props) {
  const quote = useCancellationQuote(reference, open);
  const cancel = useCancelBooking(reference);

  return (
    <ConfirmDialog
      open={open}
      title={`Cancel booking ${reference}?`}
      confirmLabel={cancel.isPending ? 'Cancelling...' : 'Cancel booking'}
      cancelLabel="Keep booking"
      destructive
      busy={cancel.isPending}
      confirmDisabled={!quote.data}
      onClose={onClose}
      onConfirm={() => cancel.mutate(undefined, { onSuccess: onClose })}
    >
      {quote.isLoading && <LoadingState label="Calculating your refund..." />}
      <ProblemAlert problem={quote.error ?? cancel.error} />
      {quote.data && (
        <>
          <Typography gutterBottom>
            Refund: <strong>{formatMoney(quote.data.refundAmount)}</strong> ({quote.data.refundPercentage}% of the
            fare)
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {quote.data.rule}. Your tickets will be voided and the seats released. This cannot be undone.
          </Typography>
        </>
      )}
    </ConfirmDialog>
  );
}
