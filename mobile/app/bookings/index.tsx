import { useQuery } from '@tanstack/react-query';
import { Link } from 'expo-router';
import { FlatList, Pressable, Text, View } from 'react-native';

import { api, call } from '../../src/api';
import { useAuth } from '../../src/auth';
import { STATUS_LABELS, formatDateTime } from '../../src/domain';
import { Button, Card, Loading, ProblemBox, styles } from '../../src/ui';

export default function MyBookingsScreen() {
  const { signedIn, signIn } = useAuth();
  const bookings = useQuery({
    queryKey: ['bookings', 'mine'],
    queryFn: () => call(() => api.bookings.listMyBookings({ page: 0, size: 50 })),
    enabled: signedIn,
  });

  if (!signedIn) {
    return (
      <View style={styles.screen}>
        <Text style={styles.title}>Sign in to see your bookings</Text>
        <Button label="Sign in" onPress={signIn} />
      </View>
    );
  }
  if (bookings.isLoading) return <Loading label="Loading your bookings..." />;
  return (
    <View style={styles.screen}>
      <ProblemBox problem={bookings.error} />
      <FlatList
        data={bookings.data?.items ?? []}
        keyExtractor={(b) => b.bookingReference}
        onRefresh={() => void bookings.refetch()}
        refreshing={bookings.isRefetching}
        ListEmptyComponent={bookings.data ? <Text style={styles.muted}>You have no bookings yet.</Text> : undefined}
        renderItem={({ item }) => (
          <Link href={`/bookings/${item.bookingReference}`} asChild>
            <Pressable
              accessibilityRole="link"
              accessibilityLabel={`Booking ${item.bookingReference}, ${STATUS_LABELS[item.status] ?? item.status}`}
            >
              <Card>
                <Text style={styles.subtitle}>{item.bookingReference} · {item.flight.flightNumber}</Text>
                <Text>{item.flight.origin} to {item.flight.destination}, {formatDateTime(item.flight.departureTime)}</Text>
                <Text style={styles.muted}>{STATUS_LABELS[item.status] ?? item.status}</Text>
              </Card>
            </Pressable>
          </Link>
        )}
      />
    </View>
  );
}
