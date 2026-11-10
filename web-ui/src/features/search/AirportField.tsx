import FlightLandIcon from '@mui/icons-material/FlightLand';
import FlightTakeoffIcon from '@mui/icons-material/FlightTakeoff';
import { Autocomplete, Box, InputAdornment, TextField, Typography } from '@mui/material';
import type { Ref } from 'react';

import type { Airport } from './airports';
import { findAirport, searchAirports } from './airports';

interface Props {
  label: string;
  /** The IATA code held by the form. */
  value: string;
  onChange: (code: string) => void;
  onBlur?: () => void;
  name?: string;
  inputRef?: Ref<HTMLInputElement>;
  error?: string;
  kind: 'origin' | 'destination';
}

/**
 * City-first airport picker: type "Dub", "London" or "Bombay" and choose from the suggestions; the form
 * keeps the IATA code. MUI's Autocomplete is an ARIA combobox (arrow keys, Enter, Escape, announced
 * options), so it stays keyboard and screen reader friendly.
 */
export function AirportField({ label, value, onChange, onBlur, name, inputRef, error, kind }: Props) {
  const selected = findAirport(value) ?? null;
  const Icon = kind === 'origin' ? FlightTakeoffIcon : FlightLandIcon;

  return (
    <Autocomplete<Airport, false, false, false>
      options={[...searchAirports('', 100)]}
      value={selected}
      onChange={(_, airport) => onChange(airport?.code ?? '')}
      onBlur={onBlur}
      filterOptions={(_, state) => searchAirports(state.inputValue)}
      getOptionLabel={(airport) => `${airport.city} (${airport.code})`}
      isOptionEqualToValue={(option, current) => option.code === current.code}
      autoHighlight
      openOnFocus
      noOptionsText="No airport matches. Try a city name, e.g. Dubai"
      sx={{ minWidth: 240, flex: 1 }}
      renderOption={({ key, ...props }, airport) => (
        <Box component="li" key={key} {...props} sx={{ display: 'flex', gap: 1.5, alignItems: 'center' }}>
          <Typography component="span" sx={{ fontWeight: 700, minWidth: 40 }}>
            {airport.code}
          </Typography>
          <Box component="span">
            <Typography component="span" sx={{ display: 'block' }}>
              {airport.city}
            </Typography>
            <Typography component="span" variant="body2" color="text.secondary">
              {airport.name}, {airport.country}
            </Typography>
          </Box>
        </Box>
      )}
      renderInput={(params) => (
        <TextField
          {...params}
          label={label}
          name={name}
          inputRef={inputRef}
          required
          error={!!error}
          helperText={error ?? 'City or airport'}
          slotProps={{
            input: {
              ...params.InputProps,
              startAdornment: (
                <InputAdornment position="start">
                  <Icon fontSize="small" aria-hidden />
                </InputAdornment>
              ),
            },
          }}
        />
      )}
    />
  );
}
