import { zodResolver } from '@hookform/resolvers/zod';
import { Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Grid, TextField } from '@mui/material';
import { useId } from 'react';
import type { Control, FieldPath } from 'react-hook-form';
import { Controller, useForm } from 'react-hook-form';

import { useCreateFlight } from '../../api/hooks';
import { useIdempotencyKey } from '../../api/idempotency';
import { ProblemAlert } from '../../components/ProblemAlert';
import type { FlightFormInput, FlightFormValues } from './flightSchema';
import { flightFormSchema, toCreateFlightRequest } from './flightSchema';

const DEFAULTS: FlightFormInput = {
  flightNumber: '',
  origin: '',
  destination: '',
  departureTime: '',
  arrivalTime: '',
  aircraftType: 'A320',
  baseFare: 200,
  currency: 'USD',
  rows: 30,
  seatLetters: 'ABCDEF',
  businessRows: 4,
};

export function CreateFlightDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const titleId = useId();
  const create = useCreateFlight();
  const keyFor = useIdempotencyKey();
  const { control, handleSubmit, reset } = useForm<FlightFormInput, unknown, FlightFormValues>({
    resolver: zodResolver(flightFormSchema),
    defaultValues: DEFAULTS,
    mode: 'onTouched',
  });

  const close = () => {
    reset(DEFAULTS);
    create.reset();
    onClose();
  };

  const onSubmit = (values: FlightFormValues) =>
    create.mutate({ request: toCreateFlightRequest(values), idempotencyKey: keyFor(values) }, { onSuccess: close });

  return (
    <Dialog open={open} onClose={create.isPending ? undefined : close} aria-labelledby={titleId} maxWidth="md">
      <Box component="form" noValidate onSubmit={handleSubmit(onSubmit)}>
        <DialogTitle id={titleId}>New flight</DialogTitle>
        <DialogContent>
          <Grid container spacing={2} sx={{ pt: 1 }}>
            <Field control={control} name="flightNumber" label="Flight number" />
            <Field control={control} name="aircraftType" label="Aircraft type" />
            <Field control={control} name="origin" label="Origin" />
            <Field control={control} name="destination" label="Destination" />
            <Field control={control} name="departureTime" label="Departure (local time)" type="datetime-local" />
            <Field control={control} name="arrivalTime" label="Arrival (local time)" type="datetime-local" />
            <Field control={control} name="baseFare" label="Base fare" type="number" />
            <Field control={control} name="currency" label="Currency" />
            <Field control={control} name="rows" label="Rows" type="number" />
            <Field control={control} name="seatLetters" label="Seat letters" />
            <Field control={control} name="businessRows" label="Business rows" type="number" />
          </Grid>
          <ProblemAlert problem={create.error} />
        </DialogContent>
        <DialogActions>
          <Button onClick={close} disabled={create.isPending}>
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={create.isPending}>
            {create.isPending ? 'Creating...' : 'Create flight'}
          </Button>
        </DialogActions>
      </Box>
    </Dialog>
  );
}

function Field({ control, name, label, type = 'text' }: { control: Control<FlightFormInput>;
  name: FieldPath<FlightFormInput>; label: string; type?: string }) {
  return (
    <Grid size={{ xs: 12, sm: 6 }}>
      <Controller
        name={name}
        control={control}
        render={({ field, fieldState }) => (
          <TextField
            {...field}
            value={field.value ?? ''}
            type={type}
            label={label}
            required
            fullWidth
            slotProps={type === 'datetime-local' ? { inputLabel: { shrink: true } } : undefined}
            error={!!fieldState.error}
            helperText={fieldState.error?.message ?? ' '}
          />
        )}
      />
    </Grid>
  );
}
