import { useRouter } from 'expo-router';
import { useState } from 'react';
import { ScrollView, Text, View } from 'react-native';

import { useAuth } from '../src/auth';
import type { SearchInput } from '../src/domain';
import { isoDate, validateSearch } from '../src/domain';
import { Button, Field, styles } from '../src/ui';

export default function SearchScreen() {
  const router = useRouter();
  const { signedIn, signIn, signOut, ready } = useAuth();
  const [form, setForm] = useState({ origin: 'DXB', destination: 'LHR', date: isoDate(1), passengers: '1' });
  const [errors, setErrors] = useState<Partial<Record<keyof SearchInput, string>>>({});

  const submit = () => {
    const input: SearchInput = {
      origin: form.origin.trim().toUpperCase(),
      destination: form.destination.trim().toUpperCase(),
      date: form.date.trim(),
      passengers: Number(form.passengers),
    };
    const found = validateSearch(input);
    setErrors(found);
    if (Object.keys(found).length === 0) {
      router.push({ pathname: '/results', params: { ...input, passengers: String(input.passengers) } });
    }
  };

  return (
    <ScrollView style={styles.screen} keyboardShouldPersistTaps="handled">
      <Text accessibilityRole="header" style={styles.title}>Where would you like to go?</Text>
      <Field label="From" value={form.origin} autoCapitalize="characters" error={errors.origin}
        onChangeText={(origin) => setForm({ ...form, origin })} />
      <Field label="To" value={form.destination} autoCapitalize="characters" error={errors.destination}
        onChangeText={(destination) => setForm({ ...form, destination })} />
      <Field label="Departure date (YYYY-MM-DD)" value={form.date} error={errors.date}
        onChangeText={(date) => setForm({ ...form, date })} />
      <Field label="Passengers" value={form.passengers} keyboardType="number-pad" error={errors.passengers}
        onChangeText={(passengers) => setForm({ ...form, passengers })} />
      <Text style={styles.muted}>We also show the two following days, in case they are cheaper.</Text>
      <Button label="Search flights" onPress={submit} />
      <View style={{ marginTop: 24 }}>
        {signedIn ? (
          <>
            <Button label="My bookings" variant="secondary" onPress={() => router.push('/bookings')} />
            <Button label="Sign out" variant="secondary" onPress={() => void signOut()} />
          </>
        ) : (
          <Button label="Sign in" variant="secondary" disabled={!ready} onPress={signIn} />
        )}
      </View>
    </ScrollView>
  );
}
