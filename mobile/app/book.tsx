import { useMutation } from '@tanstack/react-query';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useRef, useState } from 'react';
import { ScrollView, Text } from 'react-native';

import { api, call, newIdempotencyKey } from '../src/api';
import type { PassengerInput } from '../src/domain';
import { isEmail, parseIsoDate, pickSeats, validatePassenger } from '../src/domain';
import { Button, Card, Field, ProblemBox, styles } from '../src/ui';

/** Passenger details; seats are assigned automatically, together in one row where possible. */
export default function BookScreen() {
  const params = useLocalSearchParams<{ flightId: string; cabin: string; passengers: string; flightNumber: string }>();
  const router = useRouter();
  const count = Number(params.passengers ?? '1');
  const [email, setEmail] = useState('');
  const [passengers, setPassengers] = useState<PassengerInput[]>(
    Array.from({ length: count }, () => ({ firstName: '', lastName: '', dateOfBirth: '' })),
  );
  const [error, setError] = useState<string | null>(null);
  // One key per attempt: a double tap or a retry after a timeout cannot create two bookings.
  const idempotencyKey = useRef(newIdempotencyKey());

  const create = useMutation({
    mutationFn: async () => {
      const flightId = Number(params.flightId);
      const seatMap = await call(() => api.flights.getSeatMap({ flightId }));
      const seats = pickSeats(seatMap.seats, params.cabin, count);
      if (!seats) {
        throw { status: 409, title: 'Not enough seats', detail: 'This cabin has just sold out. Pick another flight.' };
      }
      return call(() =>
        api.bookings.createBooking({
          idempotencyKey: idempotencyKey.current,
          createBookingRequest: {
            flightId,
            contactEmail: email.trim(),
            passengers: passengers.map((p, i) => ({
              firstName: p.firstName.trim(),
              lastName: p.lastName.trim(),
              dateOfBirth: parseIsoDate(p.dateOfBirth.trim())!,
              seatNumber: seats[i],
            })),
          },
        }),
      );
    },
    onSuccess: (booking) => router.replace(`/bookings/${booking.bookingReference}`),
    onError: () => {
      idempotencyKey.current = newIdempotencyKey(); // the next attempt is a new request (seats may differ)
    },
  });

  const submit = () => {
    if (!isEmail(email)) return setError('Enter a valid contact email');
    for (const [i, p] of passengers.entries()) {
      const problem = validatePassenger(p);
      if (problem) return setError(`Passenger ${i + 1}: ${problem}`);
    }
    setError(null);
    create.mutate();
  };

  const update = (index: number, change: Partial<PassengerInput>) =>
    setPassengers(passengers.map((p, i) => (i === index ? { ...p, ...change } : p)));

  return (
    <ScrollView style={styles.screen} keyboardShouldPersistTaps="handled">
      <Text accessibilityRole="header" style={styles.title}>
        {params.flightNumber} · {params.cabin === 'BUSINESS' ? 'Business' : 'Economy'}
      </Text>
      <Field label="Contact email" value={email} keyboardType="email-address" autoCapitalize="none"
        onChangeText={setEmail} />
      {passengers.map((p, i) => (
        <Card key={i} label={`Passenger ${i + 1}`}>
          <Text style={styles.subtitle}>Passenger {i + 1}</Text>
          <Field label={`Passenger ${i + 1} first name`} value={p.firstName} autoCapitalize="words"
            onChangeText={(firstName) => update(i, { firstName })} />
          <Field label={`Passenger ${i + 1} last name`} value={p.lastName} autoCapitalize="words"
            onChangeText={(lastName) => update(i, { lastName })} />
          <Field label={`Passenger ${i + 1} date of birth (YYYY-MM-DD)`} value={p.dateOfBirth}
            onChangeText={(dateOfBirth) => update(i, { dateOfBirth })} />
        </Card>
      ))}
      {error ? <Text accessibilityRole="alert" style={{ color: '#a1124f' }}>{error}</Text> : null}
      <ProblemBox problem={create.error} />
      <Button label={create.isPending ? 'Holding your seats...' : 'Continue to payment'} onPress={submit}
        disabled={create.isPending} />
      <Text style={styles.muted}>Seats are held for 15 minutes while you pay.</Text>
    </ScrollView>
  );
}
