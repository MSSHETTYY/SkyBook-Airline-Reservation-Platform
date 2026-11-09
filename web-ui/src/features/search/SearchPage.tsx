import { zodResolver } from '@hookform/resolvers/zod';
import SwapHorizIcon from '@mui/icons-material/SwapHoriz';
import { Box, Button, IconButton, MenuItem, Paper, Stack, TextField, Tooltip, Typography } from '@mui/material';
import { useMemo } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { useSearchParams } from 'react-router';

import { useFlightSearch } from '../../api/hooks';
import { EmptyState, LoadingState } from '../../components/LoadingState';
import { PageTitle } from '../../components/PageTitle';
import { ProblemAlert } from '../../components/ProblemAlert';
import { FlightCard } from './FlightCard';
import type { SearchFormInput, SearchFormValues } from './searchSchema';
import { parseSearchParams, searchSchema, toSearchParams, tomorrow } from './searchSchema';

const PASSENGER_OPTIONS = [1, 2, 3, 4, 5, 6, 7, 8, 9];

export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const criteria = useMemo(() => parseSearchParams(params), [params]);
  const schema = useMemo(() => searchSchema(), []);
  const search = useFlightSearch(criteria);

  const { control, handleSubmit, getValues, setValue } = useForm<SearchFormInput, unknown, SearchFormValues>({
    resolver: zodResolver(schema),
    defaultValues: criteria ?? { origin: 'DXB', destination: 'LHR', date: tomorrow(), passengers: 1 },
    mode: 'onTouched',
  });

  const onSubmit = (values: SearchFormValues) => setParams(toSearchParams(values));

  const swap = () => {
    const { origin, destination } = getValues();
    setValue('origin', destination);
    setValue('destination', origin);
  };

  return (
    <>
      <PageTitle title="Search flights">Where would you like to go?</PageTitle>
      <Paper component="section" aria-labelledby="search-heading" sx={{ p: 3, mb: 4 }}>
        <Typography id="search-heading" variant="h2" sx={{ mb: 2 }}>
          Find a flight
        </Typography>
        <Box component="form" noValidate onSubmit={handleSubmit(onSubmit)} aria-label="Flight search">
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems={{ md: 'flex-start' }}>
            <Controller
              name="origin"
              control={control}
              render={({ field, fieldState }) => (
                <TextField
                  {...field}
                  label="From"
                  required
                  autoComplete="off"
                  slotProps={{ htmlInput: { maxLength: 3, style: { textTransform: 'uppercase' } } }}
                  error={!!fieldState.error}
                  helperText={fieldState.error?.message ?? 'Airport code'}
                />
              )}
            />
            <Tooltip title="Swap origin and destination">
              <IconButton onClick={swap} aria-label="Swap origin and destination" sx={{ alignSelf: 'center' }}>
                <SwapHorizIcon />
              </IconButton>
            </Tooltip>
            <Controller
              name="destination"
              control={control}
              render={({ field, fieldState }) => (
                <TextField
                  {...field}
                  label="To"
                  required
                  autoComplete="off"
                  slotProps={{ htmlInput: { maxLength: 3, style: { textTransform: 'uppercase' } } }}
                  error={!!fieldState.error}
                  helperText={fieldState.error?.message ?? 'Airport code'}
                />
              )}
            />
            <Controller
              name="date"
              control={control}
              render={({ field, fieldState }) => (
                <TextField
                  {...field}
                  type="date"
                  label="Departure date"
                  required
                  slotProps={{ inputLabel: { shrink: true } }}
                  error={!!fieldState.error}
                  helperText={fieldState.error?.message ?? ' '}
                />
              )}
            />
            <Controller
              name="passengers"
              control={control}
              render={({ field }) => (
                <TextField
                  select
                  label="Passengers"
                  value={field.value}
                  onChange={(event) => field.onChange(Number(event.target.value))}
                  onBlur={field.onBlur}
                  name={field.name}
                  inputRef={field.ref}
                  sx={{ minWidth: 130 }}
                  helperText=" "
                >
                  {PASSENGER_OPTIONS.map((n) => (
                    <MenuItem key={n} value={n}>
                      {n}
                    </MenuItem>
                  ))}
                </TextField>
              )}
            />
            <Button type="submit" variant="contained" size="large" sx={{ height: 56 }}>
              Search
            </Button>
          </Stack>
        </Box>
      </Paper>

      <Box component="section" aria-labelledby="results-heading" aria-busy={search.isFetching}>
        {criteria && (
          <Typography id="results-heading" variant="h2" sx={{ mb: 2 }}>
            Flights from {criteria.origin} to {criteria.destination}
          </Typography>
        )}
        {search.isLoading && <LoadingState label="Searching flights..." />}
        <ProblemAlert problem={search.error} onRetry={() => void search.refetch()} />
        {search.data && search.data.length === 0 && (
          <EmptyState title="No flights found">
            <Typography>Try another date or route.</Typography>
          </EmptyState>
        )}
        <Typography role="status" className="visually-hidden">
          {search.data ? `${search.data.length} flight${search.data.length === 1 ? '' : 's'} found` : ''}
        </Typography>
        {search.data && search.data.length > 0 && criteria && (
          <Stack spacing={2} component="ul" sx={{ listStyle: 'none', p: 0, m: 0 }}>
            {search.data.map((flight) => (
              <li key={flight.flightId}>
                <FlightCard flight={flight} passengers={criteria.passengers} />
              </li>
            ))}
          </Stack>
        )}
      </Box>
    </>
  );
}
