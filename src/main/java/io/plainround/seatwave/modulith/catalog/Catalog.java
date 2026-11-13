package io.plainround.seatwave.modulith.catalog;

import io.plainround.seatwave.modulith.catalog.internal.SeatPlanStore;
import io.plainround.seatwave.modulith.platform.Database;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The catalog module's public interface: what is on sale, where and at what price. It owns the
 * schema {@code catalog} (events, sections, seats) and answers reads only; nothing outside this
 * module writes a catalog table. Implementations are safe for concurrent use.
 */
public interface Catalog {

  /** The database role the module's statements run in; it is granted the schema catalog only. */
  String DATABASE_ROLE = "catalog_module";

  /**
   * What one seat of one event is offered for. A seat that does not exist is an answer, not an
   * error, so the result is empty rather than a failure.
   *
   * @return the offer, or empty when the event or the seat does not exist
   */
  Optional<SeatOffer> offerFor(long eventId, String seatLabel);

  /** Creates the catalog module over the deployable's database. */
  static Catalog create(Database database) {
    return new SeatPlanStore(database.forModule(DATABASE_ROLE));
  }

  /**
   * A seat's price and its show's start, as the catalog states them now.
   *
   * @param price the section's price, never negative
   * @param currency an ISO 4217 code such as {@code USD}
   * @param showStartsAt when the event starts, in UTC
   */
  record SeatOffer(BigDecimal price, String currency, Instant showStartsAt) {

    private static final String CURRENCY_FORMAT = "[A-Z]{3}";

    /** Refuses a negative price, a malformed currency or a missing start. */
    public SeatOffer {
      Objects.requireNonNull(price, "price");
      Objects.requireNonNull(currency, "currency");
      Objects.requireNonNull(showStartsAt, "showStartsAt");
      if (price.signum() < 0) {
        throw new IllegalArgumentException("price must not be negative: " + price);
      }
      if (!currency.matches(CURRENCY_FORMAT)) {
        throw new IllegalArgumentException("currency must be three capital letters: " + currency);
      }
    }
  }
}
