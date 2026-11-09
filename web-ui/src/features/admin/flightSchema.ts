import { z } from 'zod';

import type { CreateFlightRequest } from '../../api/types';

const airport = z
  .string()
  .trim()
  .regex(/^[A-Za-z]{3}$/, '3-letter airport code')
  .transform((code) => code.toUpperCase());

export const flightFormSchema = z
  .object({
    flightNumber: z
      .string()
      .trim()
      .transform((v) => v.toUpperCase())
      .pipe(z.string().regex(/^[A-Z0-9]{2}[0-9]{1,4}$/, 'Airline code plus 1-4 digits, e.g. SB101')),
    origin: airport,
    destination: airport,
    departureTime: z.string().min(1, 'Required'),
    arrivalTime: z.string().min(1, 'Required'),
    aircraftType: z.string().trim().min(2, 'At least 2 characters').max(16),
    baseFare: z.coerce.number().positive('Must be greater than 0').max(100_000),
    currency: z.string().regex(/^[A-Z]{3}$/, 'ISO currency code'),
    rows: z.coerce.number().int().min(1).max(60),
    seatLetters: z.string().trim().toUpperCase().regex(/^[A-K]{2,10}$/, '2-10 letters between A and K'),
    businessRows: z.coerce.number().int().min(0).max(20),
  })
  .refine((v) => v.origin !== v.destination, { message: 'Must differ from origin', path: ['destination'] })
  .refine((v) => new Date(v.arrivalTime) > new Date(v.departureTime), {
    message: 'Arrival must be after departure',
    path: ['arrivalTime'],
  })
  .refine((v) => new Date(v.departureTime).getTime() > Date.now(), {
    message: 'Departure must be in the future',
    path: ['departureTime'],
  })
  .refine((v) => v.businessRows < v.rows, { message: 'Fewer than the total rows', path: ['businessRows'] });

export type FlightFormInput = z.input<typeof flightFormSchema>;
export type FlightFormValues = z.output<typeof flightFormSchema>;

/** `datetime-local` values are local wall-clock time; `new Date(...)` turns them into instants. */
export function toCreateFlightRequest(v: FlightFormValues): CreateFlightRequest {
  return {
    flightNumber: v.flightNumber,
    origin: v.origin,
    destination: v.destination,
    departureTime: new Date(v.departureTime),
    arrivalTime: new Date(v.arrivalTime),
    aircraftType: v.aircraftType,
    baseFare: { amount: v.baseFare, currency: v.currency },
    layout: { rows: v.rows, seatLetters: v.seatLetters, businessRows: v.businessRows },
  };
}
