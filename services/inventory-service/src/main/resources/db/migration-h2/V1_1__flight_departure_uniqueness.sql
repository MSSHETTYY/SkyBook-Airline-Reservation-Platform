-- H2 (fast tests): a plain unique constraint; the Oracle variant uses a function-based index.
ALTER TABLE flight ADD CONSTRAINT uq_flight_number_departure UNIQUE (flight_number, departure_time);
