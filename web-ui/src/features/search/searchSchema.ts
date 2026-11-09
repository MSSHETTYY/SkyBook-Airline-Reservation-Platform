import { z } from 'zod';

import { fromIsoDate, toIsoDate } from '../../lib/format';

const airport = z
  .string()
  .trim()
  .regex(/^[A-Za-z]{3}$/, 'Enter a 3-letter airport code, for example DXB')
  .transform((code) => code.toUpperCase());

export function searchSchema(today: Date = new Date()) {
  const minDate = toIsoDate(today);
  return z
    .object({
      origin: airport,
      destination: airport,
      date: z
        .string()
        .regex(/^\d{4}-\d{2}-\d{2}$/, 'Choose a travel date')
        .refine((value) => value >= minDate, 'Choose today or a later date'),
      passengers: z.coerce.number().int().min(1).max(9),
    })
    .refine((values) => values.origin !== values.destination, {
      message: 'Destination must differ from the origin',
      path: ['destination'],
    });
}

export type SearchFormInput = z.input<ReturnType<typeof searchSchema>>;
export type SearchFormValues = z.output<ReturnType<typeof searchSchema>>;

/** Search state lives in the URL, so results can be bookmarked, shared and restored with Back. */
export function parseSearchParams(params: URLSearchParams, today: Date = new Date()): SearchFormValues | undefined {
  const result = searchSchema(today).safeParse({
    origin: params.get('origin') ?? '',
    destination: params.get('destination') ?? '',
    date: params.get('date') ?? '',
    passengers: params.get('passengers') ?? '1',
  });
  return result.success ? result.data : undefined;
}

export function toSearchParams(values: SearchFormValues): URLSearchParams {
  return new URLSearchParams({
    origin: values.origin,
    destination: values.destination,
    date: values.date,
    passengers: String(values.passengers),
  });
}

export function tomorrow(today: Date = new Date()): string {
  const next = fromIsoDate(toIsoDate(today));
  next.setDate(next.getDate() + 1);
  return toIsoDate(next);
}
