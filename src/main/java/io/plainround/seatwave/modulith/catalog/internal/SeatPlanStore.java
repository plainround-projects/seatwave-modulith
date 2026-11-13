package io.plainround.seatwave.modulith.catalog.internal;

import io.plainround.seatwave.modulith.catalog.Catalog;
import io.plainround.seatwave.modulith.platform.Database;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * The catalog's tables and its one query. Public only so the module's interface can create it; the
 * boundary tests refuse any use of this class from another module. Thread-safe: it holds no state
 * of its own.
 */
public final class SeatPlanStore implements Catalog {

  // A seat's price is a fact about its section (Module 13), so the query joins the two catalog
  // tables; every name is schema-qualified, which is what lets a test check who reads which table.
  private static final String OFFER_SQL =
      """
      SELECT sections.price, sections.currency, events.starts_at
      FROM catalog.seats seats
      JOIN catalog.sections sections
        ON sections.event_id = seats.event_id AND sections.section_code = seats.section_code
      JOIN catalog.events events ON events.event_id = seats.event_id
      WHERE seats.event_id = ? AND seats.seat_label = ?
      """;

  private final Database database;

  /** Creates the store over the module's view of the deployable's database. */
  public SeatPlanStore(Database database) {
    this.database = Objects.requireNonNull(database, "database");
  }

  @Override
  public Optional<SeatOffer> offerFor(long eventId, String seatLabel) {
    Objects.requireNonNull(seatLabel, "seatLabel");
    return database.inTransaction(connection -> {
      try (PreparedStatement query = connection.prepareStatement(OFFER_SQL)) {
        query.setLong(1, eventId);
        query.setString(2, seatLabel);
        try (ResultSet row = query.executeQuery()) {
          if (!row.next()) {
            return Optional.empty();
          }
          return Optional.of(
              new SeatOffer(
                  row.getBigDecimal(1),
                  row.getString(2),
                  row.getObject(3, OffsetDateTime.class).toInstant()));
        }
      }
    });
  }
}
