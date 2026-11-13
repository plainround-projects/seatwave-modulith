package io.plainround.seatwave.modulith.booking;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The booking module's published fact: a seat was sold to a fan. Inside the monolith it is
 * delivered in-process; once a listener leaves the deployable, the same fields travel as Module
 * 28's {@code BookingConfirmed} on {@code seatwave.bookings}. Adding a field is a contract change
 * (code guidelines, rule 53).
 *
 * @param bookingId the booking
 * @param customerId the fan who bought it
 * @param eventId the show
 * @param seatLabel the seat, for example {@code A-7}
 * @param showStartsAt when the show starts, in UTC
 * @param confirmedAt when the sale committed, in UTC
 */
public record BookingConfirmed(
    UUID bookingId,
    long customerId,
    long eventId,
    String seatLabel,
    Instant showStartsAt,
    Instant confirmedAt) {

  /** Refuses a missing field. */
  public BookingConfirmed {
    Objects.requireNonNull(bookingId, "bookingId");
    Objects.requireNonNull(seatLabel, "seatLabel");
    Objects.requireNonNull(showStartsAt, "showStartsAt");
    Objects.requireNonNull(confirmedAt, "confirmedAt");
  }
}
