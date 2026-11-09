import { Button, Link, Paper, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination, TableRow,
  Typography } from '@mui/material';
import { useState } from 'react';
import { Link as RouterLink } from 'react-router';

import { useMyBookings } from '../../api/hooks';
import { EmptyState, LoadingState } from '../../components/LoadingState';
import { PageTitle } from '../../components/PageTitle';
import { ProblemAlert } from '../../components/ProblemAlert';
import { StatusChip } from '../../components/StatusChip';
import { formatDateTime, formatMoney } from '../../lib/format';

export function MyBookingsPage() {
  const [page, setPage] = useState(0);
  const bookings = useMyBookings(page);

  return (
    <>
      <PageTitle title="My bookings" />
      {bookings.isLoading && <LoadingState label="Loading your bookings..." />}
      <ProblemAlert problem={bookings.error} onRetry={() => void bookings.refetch()} />
      {bookings.data && bookings.data.totalElements === 0 && (
        <EmptyState title="You have no bookings yet">
          <Button component={RouterLink} to="/" variant="contained">
            Search flights
          </Button>
        </EmptyState>
      )}
      {bookings.data && bookings.data.totalElements > 0 && (
        <Paper>
          <TableContainer>
            <Table aria-label="My bookings">
              <TableHead>
                <TableRow>
                  <TableCell scope="col">Reference</TableCell>
                  <TableCell scope="col">Flight</TableCell>
                  <TableCell scope="col">Departure</TableCell>
                  <TableCell scope="col">Status</TableCell>
                  <TableCell scope="col" align="right">
                    Total
                  </TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {bookings.data.items.map((b) => (
                  <TableRow key={b.bookingReference} hover>
                    <TableCell component="th" scope="row">
                      <Link component={RouterLink} to={`/bookings/${b.bookingReference}`}>
                        {b.bookingReference}
                      </Link>
                    </TableCell>
                    <TableCell>
                      {b.flight.flightNumber} {b.flight.origin}-{b.flight.destination}
                    </TableCell>
                    <TableCell>{formatDateTime(b.flight.departureTime)}</TableCell>
                    <TableCell>
                      <StatusChip status={b.status} />
                    </TableCell>
                    <TableCell align="right">{formatMoney(b.totalPrice)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          <TablePagination
            component="div"
            count={bookings.data.totalElements}
            page={page}
            rowsPerPage={bookings.data.size}
            rowsPerPageOptions={[]}
            onPageChange={(_, next) => setPage(next)}
          />
        </Paper>
      )}
      {bookings.isPlaceholderData && (
        <Typography role="status" className="visually-hidden">
          Loading page {page + 1}
        </Typography>
      )}
    </>
  );
}
