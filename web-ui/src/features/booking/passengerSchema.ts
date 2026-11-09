import { z } from 'zod';

import { toIsoDate } from '../../lib/format';

const name = (label: string) =>
  z
    .string()
    .trim()
    .min(1, `${label} is required`)
    .max(50, `${label} must be at most 50 characters`)
    .regex(/^[\p{L}][\p{L} '.-]*$/u, `${label} may only contain letters, spaces, apostrophes and hyphens`);

export function bookingFormSchema(today: Date = new Date()) {
  const todayIso = toIsoDate(today);
  return z.object({
    contactEmail: z.string().trim().min(1, 'Email is required').email('Enter a valid email address').max(254),
    passengers: z
      .array(
        z.object({
          firstName: name('First name'),
          lastName: name('Last name'),
          dateOfBirth: z
            .string()
            .regex(/^\d{4}-\d{2}-\d{2}$/, 'Enter the date of birth')
            .refine((value) => value < todayIso, 'Date of birth must be in the past')
            .refine((value) => value >= '1900-01-01', 'Enter a realistic date of birth'),
        }),
      )
      .min(1)
      .max(9),
  });
}

export type BookingFormValues = z.infer<ReturnType<typeof bookingFormSchema>>;

export function emptyPassenger(): BookingFormValues['passengers'][number] {
  return { firstName: '', lastName: '', dateOfBirth: '' };
}
