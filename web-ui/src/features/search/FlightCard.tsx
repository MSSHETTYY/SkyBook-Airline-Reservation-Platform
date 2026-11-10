import { Box, Button, Card, CardContent, Chip, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router';

import type { FlightSummary } from '../../api/types';
import { FlightStatus } from '../../api/types';
import { formatDateTime, formatDuration, formatMoney, formatTime } from '../../lib/format';
import { findAirport } from './airports';

function place(code: string): string {
  const airport = findAirport(code);
  if (!airport) return code;
  return airport.name.startsWith(airport.city) ? airport.name : `${airport.city} ${airport.name}`;
}

interface Props {
  flight: FlightSummary;
  passengers: number;
  /** Overrides the default "Select" link (used when changing the date of an existing booking). */
  action?: React.ReactNode;
}

export function seatsLeft(flight: FlightSummary): number {
  return flight.availableSeats.economy + flight.availableSeats.business;
}

export function FlightCard({ flight, passengers, action }: Props) {
  const cancelled = flight.status === FlightStatus.CANCELLED;
  const left = seatsLeft(flight);
  const bookable = !cancelled && left >= passengers;
  const headingId = `flight-${flight.flightId}`;

  return (
    <Card component="article" aria-labelledby={headingId} variant="outlined">
      <CardContent>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }}>
          <Box sx={{ flexGrow: 1 }}>
            <Typography id={headingId} variant="h3" component="h3">
              {flight.flightNumber}: {flight.origin} to {flight.destination}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {place(flight.origin)} → {place(flight.destination)}
            </Typography>
            <Typography>
              <time dateTime={flight.departureTime.toISOString()}>{formatDateTime(flight.departureTime)}</time>
              {' - '}
              <time dateTime={flight.arrivalTime.toISOString()}>{formatTime(flight.arrivalTime)}</time>
              {' · '}
              {formatDuration(flight.departureTime, flight.arrivalTime)} · {flight.aircraftType}
            </Typography>
            <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
              {cancelled ? (
                <Chip size="small" color="error" label="Cancelled" />
              ) : (
                <Chip
                  size="small"
                  variant="outlined"
                  label={`${left} seat${left === 1 ? '' : 's'} left (${flight.availableSeats.business} business)`}
                />
              )}
            </Stack>
          </Box>
          <Box sx={{ textAlign: { sm: 'right' } }}>
            <Typography variant="body2" color="text.secondary">
              from
            </Typography>
            <Typography variant="h2" component="p">
              {formatMoney(flight.fromPrice)}
            </Typography>
            {action ??
              (bookable ? (
                <Button
                  component={RouterLink}
                  to={`/flights/${flight.flightId}/book?passengers=${passengers}`}
                  variant="contained"
                  aria-label={`Select flight ${flight.flightNumber} departing ${formatDateTime(flight.departureTime)}`}
                >
                  Select
                </Button>
              ) : (
                <Typography color="text.secondary">{cancelled ? 'Not bookable' : 'Not enough seats'}</Typography>
              ))}
          </Box>
        </Stack>
      </CardContent>
    </Card>
  );
}
