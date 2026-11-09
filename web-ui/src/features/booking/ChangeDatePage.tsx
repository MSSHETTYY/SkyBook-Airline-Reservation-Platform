import { Alert, Box, Button, Paper, Stack, TextField, Typography } from '@mui/material';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';

import { useBooking, useChangeBookingDate, useFlightSearch, useSeatMap } from '../../api/hooks';
import { useIdempotencyKey } from '../../api/idempotency';
import type { Booking, FlightSummary } from '../../api/types';
import { BookingStatus } from '../../api/types';
import { EmptyState, LoadingState } from '../../components/LoadingState';
import { PageTitle } from '../../components/PageTitle';
import { ProblemAlert } from '../../components/ProblemAlert';
import { SeatMapGrid } from '../../components/SeatMapGrid';
import { formatDateTime, toIsoDate } from '../../lib/format';
import { FlightCard } from '../search/FlightCard';
import { tomorrow } from '../search/searchSchema';

export function ChangeDatePage() {
  const reference = useParams().reference ?? '';
  const booking = useBooking(reference);

  if (booking.isLoading) return <LoadingState label="Loading booking..." />;
  if (booking.error) return <ProblemAlert problem={booking.error} />;
  if (!booking.data) return null;
  if (booking.data.status !== BookingStatus.CONFIRMED) {
    return (
      <>
        <PageTitle title="Change date" />
        <Alert severity="warning">Only confirmed bookings can be changed.</Alert>
      </>
    );
  }
  return <ChangeDateForm booking={booking.data} />;
}

function ChangeDateForm({ booking }: { booking: Booking }) {
  const navigate = useNavigate();
  const passengers = booking.passengers.length;
  const [date, setDate] = useState(() => {
    const next = tomorrow();
    const current = toIsoDate(booking.flight.departureTime);
    return current > next ? current : next;
  });
  const [flight, setFlight] = useState<FlightSummary | null>(null);
  const [seats, setSeats] = useState<string[]>([]);
  const search = useFlightSearch(
    /^\d{4}-\d{2}-\d{2}$/.test(date) && date >= toIsoDate(new Date())
      ?{ origin: booking.flight.origin, destination: booking.flight.destination, date, passengers }
      : undefined,
  );
  const seatMap = useSeatMap(flight?.flightId ?? 0);
  const change = useChangeBookingDate(booking.bookingReference);
  const keyFor = useIdempotencyKey();
  const alternatives = (search.data ?? []).filter((f) => f.flightId !== booking.flight.flightId);

  const submit = () => {
    if (!flight || seats.length !== passengers) return;
    change.mutate(
      { newFlightId: flight.flightId, seatNumbers: seats, idempotencyKey: keyFor({ flight: flight.flightId, seats }) },
      { onSuccess: () => navigate(`/bookings/${booking.bookingReference}`) },
    );
  };

  return (
    <>
      <PageTitle title={`Change booking ${booking.bookingReference}`}>Change your flight date</PageTitle>
      <Typography sx={{ mt: -2, mb: 3 }}>
        Currently {booking.flight.flightNumber} on {formatDateTime(booking.flight.departureTime)}. A change fee may
        apply, and any fare difference is charged or refunded.
      </Typography>

      <Paper component="section" aria-labelledby="date-heading" sx={{ p: 3, mb: 3 }}>
        <Typography id="date-heading" variant="h2" sx={{ mb: 2 }}>
          1. Pick a new date
        </Typography>
        <TextField
          type="date"
          label="New departure date"
          value={date}
          onChange={(event) => {
            setDate(event.target.value);
            setFlight(null);
            setSeats([]);
          }}
          slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: toIsoDate(new Date()) } }}
        />
        <Box sx={{ mt: 2 }} aria-busy={search.isFetching}>
          {search.isLoading && <LoadingState label="Searching flights..." />}
          <ProblemAlert problem={search.error} />
          {search.data && alternatives.length === 0 && <EmptyState title="No other flights on this date" />}
          <Stack spacing={2} component="ul" sx={{ listStyle: 'none', p: 0, m: 0 }}>
            {alternatives.map((option) => (
              <li key={option.flightId}>
                <FlightCard
                  flight={option}
                  passengers={passengers}
                  action={
                    <Button
                      variant={flight?.flightId === option.flightId ? 'contained' : 'outlined'}
                      aria-pressed={flight?.flightId === option.flightId}
                      onClick={() => {
                        setFlight(option);
                        setSeats([]);
                      }}
                    >
                      {flight?.flightId === option.flightId ? 'Chosen' : `Choose ${option.flightNumber}`}
                    </Button>
                  }
                />
              </li>
            ))}
          </Stack>
        </Box>
      </Paper>

      {flight && (
        <Paper component="section" aria-labelledby="newseat-heading" sx={{ p: 3, mb: 3 }}>
          <Typography id="newseat-heading" variant="h2" sx={{ mb: 2 }}>
            2. Choose {passengers} seat{passengers === 1 ? '' : 's'} on {flight.flightNumber}
          </Typography>
          {seatMap.isLoading && <LoadingState label="Loading seat map..." />}
          <ProblemAlert problem={seatMap.error} />
          {seatMap.data && (
            <SeatMapGrid seats={seatMap.data.seats} selected={seats} maxSelectable={passengers} onChange={setSeats} />
          )}
          <ProblemAlert problem={change.error} />
          <Button
            variant="contained"
            sx={{ mt: 2 }}
            onClick={submit}
            disabled={seats.length !== passengers || change.isPending}
          >
            {change.isPending ? 'Changing...' : 'Confirm change'}
          </Button>
        </Paper>
      )}
    </>
  );
}
