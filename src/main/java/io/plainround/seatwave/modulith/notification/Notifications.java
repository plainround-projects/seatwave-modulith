package io.plainround.seatwave.modulith.notification;

import io.plainround.seatwave.modulith.booking.BookingConfirmed;
import io.plainround.seatwave.modulith.notification.internal.ConfirmationMailer;
import io.plainround.seatwave.modulith.platform.Database;
import io.plainround.seatwave.modulith.platform.DomainEvents;
import java.util.Objects;

/**
 * The notification module's public part. It has no methods for other modules to call: it only
 * listens for {@link BookingConfirmed}, so it depends on booking and booking knows nothing of it.
 * It owns the schema {@code notification}.
 */
public final class Notifications {

  /** The database role the module's statements run in; it is granted the schema notification. */
  public static final String DATABASE_ROLE = "notification_module";

  private Notifications() {}

  /**
   * Starts listening: every confirmed booking gets its confirmation email and its 24-hour reminder
   * queued, in the transaction that confirmed it.
   */
  public static void subscribeTo(DomainEvents events, Database database) {
    Objects.requireNonNull(events, "events");
    ConfirmationMailer mailer = new ConfirmationMailer(database.forModule(DATABASE_ROLE));
    events.subscribe(BookingConfirmed.class, mailer::queueFor);
  }
}
