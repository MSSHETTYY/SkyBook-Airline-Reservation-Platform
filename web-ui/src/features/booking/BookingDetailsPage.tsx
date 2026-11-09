import {
  Alert,
  Button,
  Grid,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router';

import { isTransient, useBooking } from '../../api/hooks';
import type { Booking } from '../../api/types';
import { BookingStatus } from '../../api/types';
import { LoadingState } from '../../components/LoadingState';
import { PageTitle } from '../../components/PageTitle';
import { ProblemAlert } from '../../components/ProblemAlert';
import { StatusChip, statusLabel } from '../../components/StatusChip';
import { formatDateTime, formatMoney } from '../../lib/format';
import { CancelBookingDialog } from './CancelBookingDialog';
import { PaymentPanel } from './PaymentPanel';

const STATUS_MESSAGES: Partial<Record<BookingStatus, string>> = {
  [BookingStatus.PAID]: 'Payment received. We are issuing your tickets, this usually takes a few seconds.',
  [BookingStatus.CONFIRMED]: 'Your booking is confirmed. Your e-ticket has been sent by email.',
  [BookingStatus.CANCELLATION_PENDING]: 'Your cancellation is being processed. The refund is on its way.',
  [BookingStatus.CANCELLED]: 'This booking has been cancelled.',
  [BookingStatus.EXPIRED]: 'This booking expired because it was not paid in time. The seats were released.',
  [BookingStatus.FAILED]: 'We could not issue your tickets. The full amount has been refunded.',
};

export function BookingDetailsPage() {
  const reference = useParams().reference ?? '';
  const booking = useBooking(reference);
  const [cancelOpen, setCancelOpen] = useState(false);

  if (booking.isLoading) return <LoadingState label="Loading booking..." />;
  if (booking.error) return <ProblemAlert problem={booking.error} onRetry={() => void booking.refetch()} />;
  if (!booking.data) return null;
  const data = booking.data;
  const payable = data.status === BookingStatus.PENDING_PAYMENT || data.status === BookingStatus.CHANGE_PENDING_PAYMENT;

  return (
    <>
      <PageTitle title={`Booking ${data.bookingReference}`}>
        Booking {data.bookingReference}
      </PageTitle>
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mt: -2, mb: 3 }}>
        <StatusChip status={data.status} />
        {isTransient(data.status) && !payable && (
          <Typography variant="body2" color="text.secondary">
            Updating automatically
          </Typography>
        )}
      </Stack>

      {/* Announced by screen readers whenever the saga moves the booking to a new state. */}
      <div role="status" aria-live="polite" className="visually-hidden">
        Booking status: {statusLabel(data.status)}
      </div>
      {STATUS_MESSAGES[data.status] && (
        <Alert severity={data.status === BookingStatus.FAILED ? 'warning' : 'info'} sx={{ mb: 3 }}>
          {STATUS_MESSAGES[data.status]}
        </Alert>
      )}

      {payable && <PaymentPanel booking={data} />}

      <Grid container spacing={3}>
        <Grid size={{ xs: 12, md: 7 }}>
          <FlightAndPassengers booking={data} />
        </Grid>
        <Grid size={{ xs: 12, md: 5 }}>
          <Summary booking={data} />
          {data.status === BookingStatus.CONFIRMED && (
            <Paper component="section" aria-labelledby="manage-heading" sx={{ p: 3, mt: 3 }}>
              <Typography id="manage-heading" variant="h2" sx={{ mb: 2 }}>
                Manage booking
              </Typography>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <Button component={RouterLink} to={`/bookings/${data.bookingReference}/change`} variant="outlined">
                  Change date
                </Button>
                <Button color="error" variant="outlined" onClick={() => setCancelOpen(true)}>
                  Cancel booking
                </Button>
              </Stack>
            </Paper>
          )}
        </Grid>
      </Grid>
      <CancelBookingDialog reference={data.bookingReference} open={cancelOpen} onClose={() => setCancelOpen(false)} />
    </>
  );
}

function FlightAndPassengers({ booking }: { booking: Booking }) {
  const { flight } = booking;
  return (
    <Paper component="section" aria-labelledby="flight-heading" sx={{ p: 3 }}>
      <Typography id="flight-heading" variant="h2" sx={{ mb: 1 }}>
        {flight.flightNumber}: {flight.origin} to {flight.destination}
      </Typography>
      <Typography sx={{ mb: 2 }}>
        Departs <time dateTime={flight.departureTime.toISOString()}>{formatDateTime(flight.departureTime)}</time>
      </Typography>
      <Table size="small" aria-label="Passengers">
        <TableHead>
          <TableRow>
            <TableCell scope="col">Passenger</TableCell>
            <TableCell scope="col">Seat</TableCell>
            <TableCell scope="col">Ticket</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {booking.passengers.map((p) => (
            <TableRow key={p.seatNumber}>
              <TableCell scope="row" component="th">
                {p.firstName} {p.lastName}
              </TableCell>
              <TableCell>{p.seatNumber}</TableCell>
              <TableCell>{p.ticketNumber ?? 'Pending'}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Paper>
  );
}

function Summary({ booking }: { booking: Booking }) {
  const rows: Array<[string, string]> = [
    ['Total price', formatMoney(booking.totalPrice)],
    ['Paid', formatMoney(booking.amountPaid)],
    ['Refunded', formatMoney(booking.amountRefunded)],
    ['Contact email', booking.contactEmail],
    ['Booked on', formatDateTime(booking.createdAt)],
  ];
  return (
    <Paper component="section" aria-labelledby="summary-heading" sx={{ p: 3 }}>
      <Typography id="summary-heading" variant="h2" sx={{ mb: 2 }}>
        Summary
      </Typography>
      <Stack component="dl" spacing={1} sx={{ m: 0 }}>
        {rows.map(([label, value]) => (
          <Stack direction="row" justifyContent="space-between" key={label} spacing={2}>
            <Typography component="dt" color="text.secondary">
              {label}
            </Typography>
            <Typography component="dd" sx={{ m: 0, textAlign: 'right', wordBreak: 'break-word' }}>
              {value}
            </Typography>
          </Stack>
        ))}
      </Stack>
    </Paper>
  );
}
