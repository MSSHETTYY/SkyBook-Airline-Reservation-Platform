import { useQuery } from '@tanstack/react-query';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { FlatList, Text, View } from 'react-native';

import { api, call } from '../src/api';
import type { FlightOffer } from '../src/api/generated/inventory';
import { useAuth } from '../src/auth';
import { formatDateTime, formatMoney, isoDate, parseIsoDate } from '../src/domain';
import { Button, Card, Loading, ProblemBox, styles } from '../src/ui';

/** Results from the v2 search API (ADR-0020): three days from the chosen date, priced per cabin. */
export default function ResultsScreen() {
  const params = useLocalSearchParams<{ origin: string; destination: string; date: string; passengers: string }>();
  const router = useRouter();
  const { signedIn, signIn } = useAuth();
  const passengers = Number(params.passengers ?? '1');
  const from = parseIsoDate(params.date ?? '') ?? new Date();

  const offers = useQuery({
    queryKey: ['offers', params.origin, params.destination, params.date, passengers],
    queryFn: () =>
      call(() =>
        api.offers.searchFlightOffers({
          origin: params.origin,
          destination: params.destination,
          departureFrom: from,
          departureTo: parseIsoDate(isoDate(2, from)),
          passengers,
          size: 30,
        }),
      ),
  });

  const book = (offer: FlightOffer, cabin: string) => {
    if (!signedIn) {
      signIn();
      return;
    }
    router.push({ pathname: '/book', params: { flightId: String(offer.flightId), cabin, passengers: String(passengers),
      flightNumber: offer.flightNumber } });
  };

  if (offers.isLoading) return <Loading label="Searching flights..." />;
  return (
    <View style={styles.screen}>
      <Text accessibilityRole="header" style={styles.title}>{params.origin} to {params.destination}</Text>
      <ProblemBox problem={offers.error} />
      <FlatList
        data={offers.data?.items ?? []}
        keyExtractor={(offer) => String(offer.flightId)}
        ListEmptyComponent={offers.data ? <Text style={styles.muted}>No flights on these dates.</Text> : null}
        renderItem={({ item }) => (
          <Card>
            <Text style={styles.subtitle}>{item.flightNumber} · {formatDateTime(item.departureTime)}</Text>
            <Text style={styles.muted}>
              {Math.floor(item.durationMinutes / 60)}h {item.durationMinutes % 60}m · {item.aircraftType}
            </Text>
            {item.cabins.map((cabin) => (
              <View key={cabin.cabinClass} style={[styles.row, { marginTop: 8 }]}>
                <Text>
                  {cabin.cabinClass === 'BUSINESS' ? 'Business' : 'Economy'}: {cabin.seatsAvailable} left
                </Text>
                {cabin.fromPrice && cabin.seatsAvailable >= passengers ? (
                  <Button
                    label={`Book from ${formatMoney(cabin.fromPrice)}`}
                    onPress={() => book(item, cabin.cabinClass)}
                  />
                ) : (
                  <Text style={styles.muted}>Not available</Text>
                )}
              </View>
            ))}
          </Card>
        )}
      />
    </View>
  );
}
