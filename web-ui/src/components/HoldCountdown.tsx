import { Typography } from '@mui/material';
import { useEffect, useState } from 'react';

interface Props {
  expiresAt: Date;
  now?: () => number;
}

function remaining(expiresAt: Date, now: number): number {
  return Math.max(0, Math.floor((expiresAt.getTime() - now) / 1000));
}

/**
 * Time left to pay. The visible clock ticks every second, but only the minute-level text is in the
 * live region, so a screen reader is told once a minute rather than every second.
 */
export function HoldCountdown({ expiresAt, now = Date.now }: Props) {
  const [seconds, setSeconds] = useState(() => remaining(expiresAt, now()));

  useEffect(() => {
    const timer = window.setInterval(() => setSeconds(remaining(expiresAt, now())), 1000);
    return () => window.clearInterval(timer);
  }, [expiresAt, now]);

  if (seconds === 0) {
    return (
      <Typography role="status" color="error">
        Your seat hold has expired.
      </Typography>
    );
  }
  const minutes = Math.floor(seconds / 60);
  const clock = `${minutes}:${(seconds % 60).toString().padStart(2, '0')}`;
  const spoken = minutes >= 1
    ? `${minutes} minute${minutes === 1 ? '' : 's'} left to pay`
    : 'Less than a minute left to pay';
  return (
    <Typography>
      Seats held for <strong aria-hidden>{clock}</strong>
      <span className="visually-hidden" role="status">
        {spoken}
      </span>
    </Typography>
  );
}
