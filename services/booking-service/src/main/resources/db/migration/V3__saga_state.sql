-- Saga state for cancellation and date change (Phase 3).

-- Refund promised when the cancellation was requested; fixed so it cannot drift while refunds are processed.
ALTER TABLE booking ADD COLUMN cancellation_refund DECIMAL(12, 2);

-- Whether the fare difference of a pending date change has been paid (the saga then applies the change).
ALTER TABLE booking_pending_change ADD COLUMN paid BOOLEAN NOT NULL DEFAULT FALSE;

-- Saga recovery: "bookings in status X not updated since T".
CREATE INDEX ix_booking_status_updated ON booking (status, updated_at);
