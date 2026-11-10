import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useState } from 'react';

import { AuthProvider } from '../src/auth';

export default function RootLayout() {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          // 4xx answers are final; only retry network/server failures, and never retry writes.
          queries: { retry: (count, error) => (error.status === 0 || error.status >= 500) && count < 2 },
          mutations: { retry: false },
        },
      }),
  );
  return (
    <QueryClientProvider client={client}>
      <AuthProvider>
        <StatusBar style="auto" />
        <Stack screenOptions={{ headerTintColor: '#0b4f8a' }}>
          <Stack.Screen name="index" options={{ title: 'SkyBook' }} />
          <Stack.Screen name="results" options={{ title: 'Flights' }} />
          <Stack.Screen name="book" options={{ title: 'Passengers' }} />
          <Stack.Screen name="bookings/index" options={{ title: 'My bookings' }} />
          <Stack.Screen name="bookings/[reference]" options={{ title: 'Booking' }} />
        </Stack>
      </AuthProvider>
    </QueryClientProvider>
  );
}
