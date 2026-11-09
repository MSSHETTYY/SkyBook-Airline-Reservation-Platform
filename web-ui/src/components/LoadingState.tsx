import { Box, CircularProgress, Typography } from '@mui/material';
import type { ReactNode } from 'react';

export function LoadingState({ label = 'Loading...' }: { label?: string }) {
  return (
    <Box role="status" sx={{ display: 'flex', gap: 2, alignItems: 'center', py: 4 }}>
      <CircularProgress size={24} aria-hidden />
      <Typography>{label}</Typography>
    </Box>
  );
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <Box sx={{ py: 6, textAlign: 'center' }}>
      <Typography variant="h2" component="p" gutterBottom>
        {title}
      </Typography>
      {children}
    </Box>
  );
}
