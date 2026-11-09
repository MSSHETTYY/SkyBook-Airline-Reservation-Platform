import AddIcon from '@mui/icons-material/Add';
import { Button, Chip, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination,
  TableRow, Typography } from '@mui/material';
import { useState } from 'react';

import { useAdminFlights, useCancelFlight } from '../../api/hooks';
import type { FlightSummary } from '../../api/types';
import { FlightStatus } from '../../api/types';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { LoadingState } from '../../components/LoadingState';
import { PageTitle } from '../../components/PageTitle';
import { ProblemAlert } from '../../components/ProblemAlert';
import { formatDateTime, formatMoney } from '../../lib/format';
import { CreateFlightDialog } from './CreateFlightDialog';

export function AdminFlightsPage() {
  const [page, setPage] = useState(0);
  const flights = useAdminFlights(page);
  const cancel = useCancelFlight();
  const [creating, setCreating] = useState(false);
  const [toCancel, setToCancel] = useState<FlightSummary | null>(null);

  return (
    <>
      <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
        <PageTitle title="Flights" />
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreating(true)}>
          New flight
        </Button>
      </Stack>
      {flights.isLoading && <LoadingState label="Loading flights..." />}
      <ProblemAlert problem={flights.error ?? cancel.error} onRetry={() => void flights.refetch()} />
      {flights.data && (
        <Paper>
          <TableContainer>
            <Table aria-label="Flights" size="small">
              <TableHead>
                <TableRow>
                  <TableCell scope="col">Flight</TableCell>
                  <TableCell scope="col">Route</TableCell>
                  <TableCell scope="col">Departure</TableCell>
                  <TableCell scope="col">Seats left (econ / bus)</TableCell>
                  <TableCell scope="col">From</TableCell>
                  <TableCell scope="col">Status</TableCell>
                  <TableCell scope="col">
                    <span className="visually-hidden">Actions</span>
                  </TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {flights.data.items.map((f) => (
                  <TableRow key={f.flightId}>
                    <TableCell component="th" scope="row">
                      {f.flightNumber}
                    </TableCell>
                    <TableCell>
                      {f.origin}-{f.destination}
                    </TableCell>
                    <TableCell>{formatDateTime(f.departureTime)}</TableCell>
                    <TableCell>
                      {f.availableSeats.economy} / {f.availableSeats.business}
                    </TableCell>
                    <TableCell>{formatMoney(f.fromPrice)}</TableCell>
                    <TableCell>
                      <Chip
                        size="small"
                        label={f.status === FlightStatus.CANCELLED ? 'Cancelled' : 'Scheduled'}
                        color={f.status === FlightStatus.CANCELLED ? 'default' : 'success'}
                        variant={f.status === FlightStatus.CANCELLED ? 'outlined' : 'filled'}
                      />
                    </TableCell>
                    <TableCell align="right">
                      {f.status === FlightStatus.SCHEDULED && (
                        <Button
                          size="small"
                          color="error"
                          onClick={() => setToCancel(f)}
                          aria-label={`Cancel flight ${f.flightNumber} on ${formatDateTime(f.departureTime)}`}
                        >
                          Cancel
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          <TablePagination
            component="div"
            count={flights.data.totalElements}
            page={page}
            rowsPerPage={flights.data.size}
            rowsPerPageOptions={[]}
            onPageChange={(_, next) => setPage(next)}
          />
        </Paper>
      )}
      <CreateFlightDialog open={creating} onClose={() => setCreating(false)} />
      <ConfirmDialog
        open={toCancel !== null}
        title={`Cancel flight ${toCancel?.flightNumber ?? ''}?`}
        confirmLabel="Cancel flight"
        destructive
        busy={cancel.isPending}
        onClose={() => setToCancel(null)}
        onConfirm={() => toCancel && cancel.mutate(toCancel.flightId, { onSuccess: () => setToCancel(null) })}
      >
        <Typography>
          The flight will no longer be bookable. Existing bookings must be re-accommodated or refunded through
          servicing.
        </Typography>
      </ConfirmDialog>
    </>
  );
}
