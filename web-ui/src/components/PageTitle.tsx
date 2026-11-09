import { Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { useEffect, useRef } from 'react';

/**
 * The page's single h1. Updates document.title and moves focus to the heading on navigation, so
 * screen reader users hear that the page changed (single-page apps do not get this for free).
 */
export function PageTitle({ children, title }: { children?: ReactNode; title: string }) {
  const ref = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    document.title = `${title} | SkyBook`;
    ref.current?.focus({ preventScroll: true });
  }, [title]);
  return (
    <Typography variant="h1" component="h1" ref={ref} tabIndex={-1} sx={{ mb: 3, outline: 'none' }}>
      {children ?? title}
    </Typography>
  );
}
