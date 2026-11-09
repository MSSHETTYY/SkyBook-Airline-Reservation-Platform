/** The model types the UI uses, re-exported under one module so features don't import generated paths. */
export type {
  Booking,
  BookingPage,
  CancellationQuote,
  CreateBookingRequest,
  FlightSnapshot,
  Passenger,
  PassengerRequest,
} from './generated/booking';
export { BookingStatus } from './generated/booking';
export type { CreateFlightRequest, FlightPage, FlightSummary, Seat, SeatMap } from './generated/inventory';
export { CabinClass, FlightStatus } from './generated/inventory';
export type { Payment, Refund } from './generated/payment';
export { PaymentStatus, SimulationRequestOutcomeEnum } from './generated/payment';
