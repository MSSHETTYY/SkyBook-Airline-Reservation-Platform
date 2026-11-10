import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { Alert, ScrollView, Text } from 'react-native';

import { api, call } from '../../src/api';
import { SimulationRequestOutcomeEnum } from '../../src/api/generated/payment';
import { STATUS_LABELS, formatDateTime, formatMoney, isTransient } from '../../src/domain';
import { Button, Card, Loading, ProblemBox, styles } from '../../src/ui';

export const POLL_MS = 2000;

export default function BookingScreen() {
  const { reference } = useLocalSearchParams<{ reference: string }>();
  const client = useQueryClient();
  const [declined, setDeclined] = useState(false);

  // Poll while the saga is still moving the booking (payment, ticketing, refund), then stop.
  const booking = useQuery({
    queryKey: ['booking', reference],
    queryFn: () => call(() => api.bookings.getBooking({ bookingReference: reference })),
    refetchInterval: (query) => (query.state.data && isTransient(query.state.data.status) ? POLL_MS : false),
  });
  const refresh = () => client.invalidateQueries({ queryKey: ['booking', reference] });

  const pay = useMutation({
    mutationFn: (succeed: boolean) =>
      call(() =>
        api.simulation.simulatePaymentOutcome({
          paymentId: booking.data!.payment!.paymentId,
          simulationRequest: { outcome: succeed ? SimulationRequestOutcomeEnum.SUCCEEDED : SimulationRequestOutcomeEnum.FAILED },
        }),
      ),
    onSuccess: (_, succeed) => {
      setDeclined(!succeed);
      void refresh();
    },
  });

  const cancel = useMutation({
    mutationFn: () => call(() => api.bookings.cancelBooking({ bookingReference: reference })),
    onSuccess: (updated) => client.setQueryData(['booking', reference], updated),
  });

  const confirmCancel = async () => {
    try {
      const quote = await call(() => api.bookings.getCancellationQuote({ bookingReference: reference }));
      Alert.alert(
        `Cancel ${reference}?`,
        `Refund: ${formatMoney(quote.refundAmount)} (${quote.refundPercentage}%). ${quote.rule}.`,
        [
          { text: 'Keep booking', style: 'cancel' },
          { text: 'Cancel booking', style: 'destructive', onPress: () => cancel.mutate() },
        ],
      );
    } catch (problem) {
      Alert.alert('Cannot cancel', (problem as { detail?: string }).detail ?? 'Please try again later.');
    }
  };

  if (booking.isLoading) return <Loading label="Loading booking..." />;
  if (!booking.data) return <ProblemBox problem={booking.error} />;
  const b = booking.data;
  const payable = b.status === 'PENDING_PAYMENT' && !!b.payment;

  return (
    <ScrollView style={styles.screen}>
      <Text accessibilityRole="header" style={styles.title}>Booking {b.bookingReference}</Text>
      <Text accessibilityLiveRegion="polite" style={styles.subtitle}>{STATUS_LABELS[b.status] ?? b.status}</Text>
      <Card>
        <Text style={styles.subtitle}>{b.flight.flightNumber}: {b.flight.origin} to {b.flight.destination}</Text>
        <Text>Departs {formatDateTime(b.flight.departureTime)}</Text>
        {b.passengers.map((p) => (
          <Text key={p.seatNumber}>
            {p.firstName} {p.lastName} · seat {p.seatNumber} · {p.ticketNumber ? `ticket ${p.ticketNumber}` : 'ticket pending'}
          </Text>
        ))}
        <Text style={styles.muted}>Total {formatMoney(b.totalPrice)} · refunded {formatMoney(b.amountRefunded)}</Text>
      </Card>
      <ProblemBox problem={pay.error ?? cancel.error} />
      {payable && (
        <Card>
          <Text style={styles.subtitle}>Pay {formatMoney(b.totalPrice)}</Text>
          <Text style={styles.muted}>Test mode: choose an outcome to simulate the payment provider.</Text>
          {declined ? <Text accessibilityRole="alert">The card was declined (simulated). Try again.</Text> : null}
          <Button label={`Pay ${formatMoney(b.totalPrice)} (test)`} disabled={pay.isPending} onPress={() => pay.mutate(true)} />
          <Button label="Simulate declined card" variant="secondary" disabled={pay.isPending}
            onPress={() => pay.mutate(false)} />
        </Card>
      )}
      {b.status === 'CONFIRMED' && (
        <Button label="Cancel booking" variant="danger" disabled={cancel.isPending} onPress={() => void confirmCancel()} />
      )}
    </ScrollView>
  );
}
