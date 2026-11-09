import type { ChipProps } from '@mui/material';
import { Chip } from '@mui/material';

import { BookingStatus } from '../api/types';

const LABELS: Record<BookingStatus, { label: string; color: ChipProps['color'] }> = {
  [BookingStatus.PENDING_PAYMENT]: { label: 'Awaiting payment', color: 'warning' },
  [BookingStatus.PAID]: { label: 'Issuing tickets', color: 'info' },
  [BookingStatus.CONFIRMED]: { label: 'Confirmed', color: 'success' },
  [BookingStatus.CHANGE_PENDING_PAYMENT]: { label: 'Change awaiting payment', color: 'warning' },
  [BookingStatus.CANCELLATION_PENDING]: { label: 'Cancellation in progress', color: 'info' },
  [BookingStatus.CANCELLED]: { label: 'Cancelled', color: 'default' },
  [BookingStatus.EXPIRED]: { label: 'Expired', color: 'default' },
  [BookingStatus.FAILED]: { label: 'Failed - refunded', color: 'error' },
};

export function statusLabel(status: BookingStatus): string {
  return LABELS[status].label;
}

/** Status as text, never colour alone (WCAG 1.4.1). */
export function StatusChip({ status }: { status: BookingStatus }) {
  const { label, color } = LABELS[status];
  return <Chip size="small" label={label} color={color} variant={color === 'default' ? 'outlined' : 'filled'} />;
}
