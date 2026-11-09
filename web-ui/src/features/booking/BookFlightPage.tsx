import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, Divider, Grid, Paper, Stack, TextField, Typography } from '@mui/material';
import { useEffect, useMemo, useState } from 'react';
import type { Control } from 'react-hook-form';
import { Controller, useFieldArray, useForm } from 'react-hook-form';
import { useNavigate, useParams, useSearchParams } from 'react-router';

import { useCreateBooking, useFlight, useSeatMap } from '../../api/hooks';
import { useIdempotencyKey } from '../../api/idempotency';
import type { CreateBookingRequest, Seat } from '../../api/types';
import { useSession } from '../../auth/session';
import { LoadingState } from '../../components/LoadingState';
import { PageTitle } from '../../components/PageTitle';
import { ProblemAlert } from '../../components/ProblemAlert';
import { SeatMapGrid } from '../../components/SeatMapGrid';
import { formatDateTime, formatMoney, fromIsoDate } from '../../lib/format';
import type { BookingFormValues } from './passengerSchema';
import { bookingFormSchema, emptyPassenger } from './passengerSchema';

function clampPassengers(raw: string | null): number {
  const n = Number(raw ?? '1');
  return Number.isInteger(n) && n >= 1 && n <= 9 ? n : 1;
}

export function totalPrice(seats: Seat[], selected: string[]): { amount: number; currency: string } | undefined {
  const chosen = seats.filter((seat) => selected.includes(seat.seatNumber));
  if (chosen.length === 0) return undefined;
  // Sum in minor units to avoid floating point drift (0.1 + 0.2).
  const cents = chosen.reduce((sum, seat) => sum + Math.round(seat.price.amount * 100), 0);
  return { amount: cents / 100, currency: chosen[0].price.currency };
}

export function BookFlightPage() {
  const flightId = Number(useParams().flightId);
  const [params] = useSearchParams();
  const passengerCount = clampPassengers(params.get('passengers'));
  const navigate = useNavigate();
  const { user } = useSession();

  const flight = useFlight(flightId);
  const seatMap = useSeatMap(flightId);
  const createBooking = useCreateBooking();
  const keyFor = useIdempotencyKey();
  const [selected, setSelected] = useState<string[]>([]);
  const [seatError, setSeatError] = useState<string | null>(null);

  const schema = useMemo(() => bookingFormSchema(), []);
  const { control, handleSubmit } = useForm<BookingFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      contactEmail: user?.email ?? '',
      passengers: Array.from({ length: passengerCount }, emptyPassenger),
    },
    mode: 'onTouched',
  });
  const { fields } = useFieldArray({ control, name: 'passengers' });

  // A seat taken by someone else between loading the map and submitting: refresh the map and drop it.
  const { error: bookingError } = createBooking;
  const { refetch: refetchSeats } = seatMap;
  useEffect(() => {
    if (bookingError?.code === 'SEAT_UNAVAILABLE') {
      void refetchSeats().then((result) => {
        const free = new Set(result.data?.seats.filter((s) => s.available).map((s) => s.seatNumber));
        setSelected((current) => current.filter((seat) => free.has(seat)));
      });
    }
  }, [bookingError, refetchSeats]);

  const price = seatMap.data ? totalPrice(seatMap.data.seats, selected) : undefined;

  const onSubmit = (values: BookingFormValues) => {
    if (selected.length !== passengerCount) {
      setSeatError(`Select ${passengerCount} seat${passengerCount === 1 ? '' : 's'} before continuing.`);
      document.getElementById('seat-heading')?.focus();
      return;
    }
    setSeatError(null);
    const request: CreateBookingRequest = {
      flightId,
      contactEmail: values.contactEmail,
      passengers: values.passengers.map((p, i) => ({
        firstName: p.firstName,
        lastName: p.lastName,
        dateOfBirth: fromIsoDate(p.dateOfBirth),
        seatNumber: selected[i],
      })),
    };
    createBooking.mutate(
      { request, idempotencyKey: keyFor({ ...values, flightId, selected }) },
      { onSuccess: (booking) => navigate(`/bookings/${booking.bookingReference}`, { replace: true }) },
    );
  };

  if (flight.isLoading || seatMap.isLoading) return <LoadingState label="Loading seat map..." />;
  if (flight.error || seatMap.error) {
    return <ProblemAlert problem={flight.error ?? seatMap.error} onRetry={() => void flight.refetch()} />;
  }
  if (!flight.data || !seatMap.data) return null;

  return (
    <>
      <PageTitle title={`Book ${flight.data.flightNumber}`}>
        {flight.data.flightNumber}: {flight.data.origin} to {flight.data.destination}
      </PageTitle>
      <Typography sx={{ mt: -2, mb: 3 }}>
        Departs {formatDateTime(flight.data.departureTime)} · {passengerCount} passenger
        {passengerCount === 1 ? '' : 's'}
      </Typography>

      <Box component="form" noValidate onSubmit={handleSubmit(onSubmit)} aria-label="Booking details">
        <Grid container spacing={3}>
          <Grid size={{ xs: 12, md: 7 }}>
            <Paper component="section" aria-labelledby="seat-heading" sx={{ p: 3 }}>
              <Typography id="seat-heading" variant="h2" tabIndex={-1} sx={{ mb: 1 }}>
                1. Choose seats
              </Typography>
              {seatError && (
                <Alert severity="error" sx={{ mb: 2 }}>
                  {seatError}
                </Alert>
              )}
              <SeatMapGrid
                seats={seatMap.data.seats}
                selected={selected}
                maxSelectable={passengerCount}
                onChange={(next) => {
                  setSelected(next);
                  if (next.length === passengerCount) setSeatError(null);
                }}
              />
            </Paper>
          </Grid>
          <Grid size={{ xs: 12, md: 5 }}>
            <Paper component="section" aria-labelledby="passenger-heading" sx={{ p: 3 }}>
              <Typography id="passenger-heading" variant="h2" sx={{ mb: 2 }}>
                2. Passenger details
              </Typography>
              <Stack spacing={3}>
                <Controller
                  name="contactEmail"
                  control={control}
                  render={({ field, fieldState }) => (
                    <TextField
                      {...field}
                      type="email"
                      label="Contact email"
                      autoComplete="email"
                      required
                      error={!!fieldState.error}
                      helperText={fieldState.error?.message ?? 'We send the e-ticket here'}
                    />
                  )}
                />
                {fields.map((field, index) => (
                  <PassengerFields key={field.id} index={index} control={control} seat={selected[index]} />
                ))}
              </Stack>
            </Paper>
          </Grid>
        </Grid>

        <Paper component="section" aria-labelledby="review-heading" sx={{ p: 3, mt: 3 }}>
          <Typography id="review-heading" variant="h2" sx={{ mb: 1 }}>
            3. Review
          </Typography>
          <Typography>
            Total: <strong>{price ? formatMoney(price) : 'select seats to see the price'}</strong>
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Your seats are held for 15 minutes while you pay.
          </Typography>
          <ProblemAlert problem={createBooking.error} />
          <Divider sx={{ mb: 2 }} />
          <Button type="submit" variant="contained" size="large" disabled={createBooking.isPending}>
            {createBooking.isPending ? 'Holding your seats...' : 'Continue to payment'}
          </Button>
        </Paper>
      </Box>
    </>
  );
}

function PassengerFields({ index, control, seat }: { index: number; control: Control<BookingFormValues>;
  seat: string | undefined }) {
  const legend = `Passenger ${index + 1}${seat ? ` - seat ${seat}` : ''}`;
  return (
    <Box component="fieldset" sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2, p: 2, m: 0 }}>
      <Typography component="legend" variant="h3" sx={{ px: 1 }}>
        {legend}
      </Typography>
      <Stack spacing={2}>
        <Controller
          name={`passengers.${index}.firstName`}
          control={control}
          render={({ field, fieldState }) => (
            <TextField
              {...field}
              label="First name"
              autoComplete={index === 0 ? 'given-name' : 'off'}
              required
              error={!!fieldState.error}
              helperText={fieldState.error?.message ?? 'As shown on the passport'}
            />
          )}
        />
        <Controller
          name={`passengers.${index}.lastName`}
          control={control}
          render={({ field, fieldState }) => (
            <TextField
              {...field}
              label="Last name"
              autoComplete={index === 0 ? 'family-name' : 'off'}
              required
              error={!!fieldState.error}
              helperText={fieldState.error?.message ?? ' '}
            />
          )}
        />
        <Controller
          name={`passengers.${index}.dateOfBirth`}
          control={control}
          render={({ field, fieldState }) => (
            <TextField
              {...field}
              type="date"
              label="Date of birth"
              autoComplete={index === 0 ? 'bday' : 'off'}
              required
              slotProps={{ inputLabel: { shrink: true } }}
              error={!!fieldState.error}
              helperText={fieldState.error?.message ?? ' '}
            />
          )}
        />
      </Stack>
    </Box>
  );
}
