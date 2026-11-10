import type { ReactNode } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import type { ApiProblem } from './api';

/**
 * Tiny accessible component set: every control has an accessibilityRole and label, touch targets are at
 * least 48 dp, and colours meet 4.5:1 contrast on white.
 */
export const colors = { primary: '#0b4f8a', danger: '#a1124f', text: '#1a1a1a', muted: '#555555', border: '#c4c4c4' };

export function Button({ label, onPress, disabled, variant = 'primary' }: {
  label: string; onPress: () => void; disabled?: boolean; variant?: 'primary' | 'secondary' | 'danger';
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: !!disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.button,
        variant === 'secondary' && styles.secondary,
        variant === 'danger' && { backgroundColor: colors.danger },
        (pressed || disabled) && { opacity: 0.6 },
      ]}
    >
      <Text style={[styles.buttonText, variant === 'secondary' && { color: colors.primary }]}>{label}</Text>
    </Pressable>
  );
}

export function Field({ label, value, onChangeText, error, ...props }: {
  label: string; value: string; onChangeText: (text: string) => void; error?: string;
  autoCapitalize?: 'none' | 'characters' | 'words'; keyboardType?: 'default' | 'email-address' | 'number-pad';
  placeholder?: string;
}) {
  return (
    <View style={styles.field}>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        accessibilityLabel={label}
        accessibilityHint={error}
        value={value}
        onChangeText={onChangeText}
        style={[styles.input, !!error && { borderColor: colors.danger }]}
        {...props}
      />
      {error ? <Text accessibilityRole="alert" style={styles.error}>{error}</Text> : null}
    </View>
  );
}

export function Loading({ label = 'Loading...' }: { label?: string }) {
  return (
    <View accessible accessibilityLabel={label} style={styles.center}>
      <ActivityIndicator color={colors.primary} />
      <Text style={styles.muted}>{label}</Text>
    </View>
  );
}

export function ProblemBox({ problem }: { problem: ApiProblem | null | undefined }) {
  if (!problem) return null;
  return (
    <View accessibilityRole="alert" style={styles.problem}>
      <Text style={styles.problemTitle}>{problem.title}</Text>
      {problem.detail ? <Text>{problem.detail}</Text> : null}
    </View>
  );
}

export function Card({ children, label }: { children: ReactNode; label?: string }) {
  return <View accessible={false} accessibilityLabel={label} style={styles.card}>{children}</View>;
}

export const styles = StyleSheet.create({
  screen: { flex: 1, padding: 16, backgroundColor: '#ffffff' },
  title: { fontSize: 22, fontWeight: '700', color: colors.text, marginBottom: 12 },
  subtitle: { fontSize: 17, fontWeight: '600', color: colors.text },
  muted: { color: colors.muted, marginTop: 4 },
  button: { minHeight: 48, borderRadius: 10, backgroundColor: colors.primary, justifyContent: 'center',
    alignItems: 'center', paddingHorizontal: 16, marginVertical: 6 },
  secondary: { backgroundColor: '#ffffff', borderWidth: 2, borderColor: colors.primary },
  buttonText: { color: '#ffffff', fontSize: 16, fontWeight: '600' },
  field: { marginBottom: 12 },
  label: { fontWeight: '600', marginBottom: 4, color: colors.text },
  input: { minHeight: 48, borderWidth: 1, borderColor: colors.border, borderRadius: 8, paddingHorizontal: 12,
    fontSize: 16, color: colors.text },
  error: { color: colors.danger, marginTop: 4 },
  center: { padding: 24, alignItems: 'center' },
  problem: { backgroundColor: '#fdecee', borderRadius: 8, padding: 12, marginVertical: 8 },
  problemTitle: { fontWeight: '700', color: colors.danger },
  card: { borderWidth: 1, borderColor: colors.border, borderRadius: 12, padding: 14, marginBottom: 12 },
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
});
