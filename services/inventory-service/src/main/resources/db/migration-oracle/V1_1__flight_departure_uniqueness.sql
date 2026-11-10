-- Oracle: UNIQUE constraints are not allowed on TIMESTAMP WITH TIME ZONE (ORA-02329); a unique
-- function-based index on the UTC instant enforces the same rule (one departure per flight number and time).
CREATE UNIQUE INDEX uq_flight_number_departure ON flight (flight_number, SYS_EXTRACT_UTC(departure_time));
