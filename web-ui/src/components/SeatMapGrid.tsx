import CheckIcon from '@mui/icons-material/Check';
import CloseIcon from '@mui/icons-material/Close';
import { Box, Stack, Typography } from '@mui/material';
import { useId, useMemo } from 'react';

import type { Seat } from '../api/types';
import { CabinClass } from '../api/types';
import { formatMoney } from '../lib/format';

interface Props {
  seats: Seat[];
  selected: string[];
  maxSelectable: number;
  onChange: (selected: string[]) => void;
  /** Accessible name of the seat group. */
  label?: string;
}

interface Row {
  number: number;
  seats: Map<string, Seat>;
}

export function seatRow(seatNumber: string): number {
  return Number.parseInt(seatNumber, 10);
}

export function seatLetter(seatNumber: string): string {
  return seatNumber.replace(/^\d+/, '');
}

function layout(seats: Seat[]): { rows: Row[]; letters: string[] } {
  const letters = [...new Set(seats.map((s) => seatLetter(s.seatNumber)))].sort();
  const byRow = new Map<number, Row>();
  for (const seat of seats) {
    const number = seatRow(seat.seatNumber);
    const row = byRow.get(number) ?? { number, seats: new Map() };
    row.seats.set(seatLetter(seat.seatNumber), seat);
    byRow.set(number, row);
  }
  return { rows: [...byRow.values()].sort((a, b) => a.number - b.number), letters };
}

function seatDescription(seat: Seat, isSelected: boolean): string {
  const cabin = seat.cabinClass === CabinClass.BUSINESS ? 'business' : 'economy';
  const state = !seat.available ? 'unavailable' : isSelected ? 'selected' : 'available';
  return `Seat ${seat.seatNumber}, ${cabin}, ${formatMoney(seat.price)}, ${state}`;
}

/**
 * Accessible seat picker: each seat is a real button with aria-pressed and a full spoken label
 * (number, cabin, price, state). State is shown with icons as well as colour (WCAG 1.4.1), and the
 * whole map works with Tab / Space / Enter.
 */
export function SeatMapGrid({ seats, selected, maxSelectable, onChange, label = 'Seat map' }: Props) {
  const { rows, letters } = useMemo(() => layout(seats), [seats]);
  const statusId = useId();
  const aisleAfter = Math.ceil(letters.length / 2) - 1;
  const full = selected.length >= maxSelectable;

  function toggle(seat: Seat) {
    if (!seat.available) return;
    if (selected.includes(seat.seatNumber)) {
      onChange(selected.filter((s) => s !== seat.seatNumber));
    } else if (maxSelectable === 1) {
      onChange([seat.seatNumber]);
    } else if (!full) {
      onChange([...selected, seat.seatNumber]);
    }
  }

  return (
    <Box>
      <Legend />
      <Typography id={statusId} role="status" sx={{ my: 1 }}>
        {selected.length} of {maxSelectable} seat{maxSelectable === 1 ? '' : 's'} selected
        {selected.length > 0 ? `: ${selected.join(', ')}` : ''}
        {full && maxSelectable > 1 ? '. Deselect a seat to pick a different one.' : ''}
      </Typography>
      <Box
        role="group"
        aria-label={label}
        aria-describedby={statusId}
        sx={{ overflowX: 'auto', py: 1 }}
      >
        <Stack spacing={0.75} sx={{ width: 'max-content', mx: 'auto' }}>
          <Stack direction="row" spacing={0.75} aria-hidden>
            <Box sx={{ width: 32 }} />
            {letters.map((letter, i) => (
              <Box key={letter} sx={{ width: 40, textAlign: 'center', mr: i === aisleAfter ? 3 : 0 }}>
                <Typography variant="caption">{letter}</Typography>
              </Box>
            ))}
          </Stack>
          {rows.map((row) => (
            <Stack direction="row" spacing={0.75} key={row.number} alignItems="center">
              <Typography variant="caption" sx={{ width: 32, textAlign: 'right' }} aria-hidden>
                {row.number}
              </Typography>
              {letters.map((letter, i) => {
                const seat = row.seats.get(letter);
                const style = { mr: i === aisleAfter ? 3 : 0 };
                if (!seat) return <Box key={letter} sx={{ width: 40, ...style }} />;
                const isSelected = selected.includes(seat.seatNumber);
                const blocked = !seat.available || (!isSelected && full && maxSelectable > 1);
                return (
                  <SeatButton
                    key={letter}
                    seat={seat}
                    selected={isSelected}
                    blocked={blocked}
                    onClick={() => toggle(seat)}
                    sx={style}
                  />
                );
              })}
            </Stack>
          ))}
        </Stack>
      </Box>
    </Box>
  );
}

interface SeatButtonProps {
  seat: Seat;
  selected: boolean;
  blocked: boolean;
  onClick: () => void;
  sx: object;
}

function SeatButton({ seat, selected, blocked, onClick, sx }: SeatButtonProps) {
  const business = seat.cabinClass === CabinClass.BUSINESS;
  return (
    <Box
      component="button"
      type="button"
      aria-pressed={selected}
      aria-label={seatDescription(seat, selected)}
      // Unavailable seats stay focusable (aria-disabled, not disabled) so their state can be heard.
      aria-disabled={blocked || undefined}
      onClick={onClick}
      title={seatDescription(seat, selected)}
      sx={{
        width: 40,
        height: 40,
        borderRadius: business ? '10px' : '6px',
        border: '2px solid',
        borderColor: selected ? 'primary.dark' : business ? 'secondary.main' : 'grey.500',
        bgcolor: !seat.available ? 'grey.300' : selected ? 'primary.main' : 'background.paper',
        color: selected ? 'primary.contrastText' : 'text.primary',
        cursor: blocked ? 'not-allowed' : 'pointer',
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        p: 0,
        font: 'inherit',
        fontSize: 12,
        ...sx,
      }}
    >
      {!seat.available ? <CloseIcon fontSize="small" aria-hidden /> : selected ? <CheckIcon fontSize="small" aria-hidden /> : null}
    </Box>
  );
}

function Legend() {
  const item = (label: string, sx: object, icon?: React.ReactNode) => (
    <Stack direction="row" spacing={1} alignItems="center">
      <Box
        aria-hidden
        sx={{ width: 22, height: 22, border: '2px solid', display: 'inline-flex', alignItems: 'center',
          justifyContent: 'center', ...sx }}
      >
        {icon}
      </Box>
      <Typography variant="body2">{label}</Typography>
    </Stack>
  );
  return (
    <Stack direction="row" spacing={2} useFlexGap flexWrap="wrap" component="ul" aria-label="Legend"
      sx={{ listStyle: 'none', p: 0, m: 0 }}>
      <li>{item('Economy', { borderColor: 'grey.500', borderRadius: '4px' })}</li>
      <li>{item('Business', { borderColor: 'secondary.main', borderRadius: '7px' })}</li>
      <li>{item('Selected', { borderColor: 'primary.dark', bgcolor: 'primary.main', color: 'white' },
        <CheckIcon sx={{ fontSize: 14 }} />)}</li>
      <li>{item('Unavailable', { borderColor: 'grey.500', bgcolor: 'grey.300' }, <CloseIcon sx={{ fontSize: 14 }} />)}</li>
    </Stack>
  );
}
