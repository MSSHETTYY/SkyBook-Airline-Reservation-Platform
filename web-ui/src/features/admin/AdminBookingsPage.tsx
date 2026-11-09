import { Button, MenuItem, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination,
  TableRow, TextField } from '@mui/material';
import { useState } from 'react';

import type { AdminBookingFilter } from '../../api/hooks';
import { useAdminBookings } from '../../api/hooks';
import { BookingStatus } from '../../api/types';
import { EmptyState, LoadingState } from '../../components/LoadingState';
import { PageTitle } from '../../components/PageTitle';
import { ProblemAlert } from '../../components/ProblemAlert';
import { StatusChip, statusLabel } from '../../components/StatusChip';
import { formatDateTime, formatMoney } from '../../lib/format';
import { BookingServicingDrawer } from './BookingServicingDrawer';

const ALL = 'ALL';

export function AdminBookingsPage() {
  const [filter, setFilter] = useState<AdminBookingFilter>({ page: 0 });
  const [flightIdText, setFlightIdText] = useState('');
  const [selected, setSelected] = useState<string | undefined>();
  const bookings = useAdminBookings(filter);

  const applyFlightFilter = () => {
    const id = Number(flightIdText);
    setFilter((f) => ({ ...f, page: 0, flightId: flightIdText && Number.isInteger(id) && id > 0 ? id : undefined }));
  };

  return (
    <>
      <PageTitle title="Booking servicing" />
      <Paper component="section" aria-label="Filters" sx={{ p: 2, mb: 3 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }}>
          <TextField
            select
            label="Status"
            value={filter.status ?? ALL}
            onChange={(e) =>
              setFilter((f) => ({
                ...f,
                page: 0,
                status: e.target.value === ALL ? undefined : (e.target.value as BookingStatus),
              }))
            }
            sx={{ minWidth: 220 }}
          >
            <MenuItem value={ALL}>All statuses</MenuItem>
            {Object.values(BookingStatus).map((s) => (
              <MenuItem key={s} value={s}>
                {statusLabel(s)}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label="Flight ID"
            value={flightIdText}
            onChange={(e) => setFlightIdText(e.target.value.replace(/\D/g, ''))}
            onKeyDown={(e) => e.key === 'Enter' && applyFlightFilter()}
            slotProps={{ htmlInput: { inputMode: 'numeric' } }}
          />
          <Button variant="outlined" onClick={applyFlightFilter}>
            Apply
          </Button>
        </Stack>
      </Paper>

      {bookings.isLoading && <LoadingState label="Loading bookings..." />}
      <ProblemAlert problem={bookings.error} onRetry={() => void bookings.refetch()} />
      {bookings.data && bookings.data.totalElements === 0 && <EmptyState title="No bookings match these filters" />}
      {bookings.data && bookings.data.totalElements > 0 && (
        <Paper>
          <TableContainer>
            <Table aria-label="Bookings" size="small">
              <TableHead>
                <TableRow>
                  <TableCell scope="col">Reference</TableCell>
                  <TableCell scope="col">Flight</TableCell>
                  <TableCell scope="col">Departure</TableCell>
                  <TableCell scope="col">Passengers</TableCell>
                  <TableCell scope="col">Status</TableCell>
                  <TableCell scope="col" align="right">
                    Paid / refunded
                  </TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {bookings.data.items.map((b) => (
                  <TableRow key={b.bookingReference} hover selected={selected === b.bookingReference}>
                    <TableCell component="th" scope="row">
                      <Button size="small" onClick={() => setSelected(b.bookingReference)}
                        aria-label={`Open booking ${b.bookingReference}`}>
                        {b.bookingReference}
                      </Button>
                    </TableCell>
                    <TableCell>
                      {b.flight.flightNumber} {b.flight.origin}-{b.flight.destination}
                    </TableCell>
                    <TableCell>{formatDateTime(b.flight.departureTime)}</TableCell>
                    <TableCell>{b.passengers.length}</TableCell>
                    <TableCell>
                      <StatusChip status={b.status} />
                    </TableCell>
                    <TableCell align="right">
                      {formatMoney(b.amountPaid)} / {formatMoney(b.amountRefunded)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          <TablePagination
            component="div"
            count={bookings.data.totalElements}
            page={filter.page}
            rowsPerPage={bookings.data.size}
            rowsPerPageOptions={[]}
            onPageChange={(_, page) => setFilter((f) => ({ ...f, page }))}
          />
        </Paper>
      )}
      <BookingServicingDrawer reference={selected} onClose={() => setSelected(undefined)} />
    </>
  );
}
