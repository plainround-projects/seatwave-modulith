package io.plainround.seatwave.modulith.inventory;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.modulith.catalog.Catalog;
import io.plainround.seatwave.modulith.inventory.internal.HoldStore;
import io.plainround.seatwave.modulith.platform.Database;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The inventory module's public interface: who may claim which seat, and for how long. It owns the
 * schema {@code inventory} and the hold's states (ACTIVE, CONFIRMING, CONFIRMED, RELEASED,
 * EXPIRED). Every method runs in the caller's transaction when there is one; every expected refusal
 * is a {@link Refusal}, never an exception. Implementations are safe for concurrent use.
 */
public interface Inventory {

  /** The database role the module's statements run in; it is granted the schema inventory only. */
  String DATABASE_ROLE = "inventory_module";

  /** Grants a fan a hold on a free seat the catalog offers, or says why not. */
  Result<Held, Refusal> hold(long customerId, long eventId, String seatLabel);

  /**
   * Takes the semantic lock (Module 28): this fan's ACTIVE, unexpired hold becomes CONFIRMING for
   * one booking, which expiry and other fans then respect. Repeating it for the same booking
   * answers with the same seat again.
   */
  Result<ClaimedSeat, Refusal> lockForPayment(UUID holdId, long customerId, UUID bookingId);

  /**
   * Turns this booking's CONFIRMING hold into a sale; repeating it answers with the same seat.
   *
   * @return the seat sold, or a refusal if the hold is no longer this booking's (released behind
   *     the saga's back), which the booking saga answers with a refund
   */
  Result<ClaimedSeat, Refusal> confirm(UUID holdId, UUID bookingId);

  /** Gives this booking's CONFIRMING hold back to the sale; repeating it changes nothing. */
  void release(UUID holdId, UUID bookingId);

  /**
   * Creates the inventory module over the deployable's database. It asks the catalog which seats
   * exist: inventory conforms to the catalog's seat identity, {@code (event_id, seat_label)}.
   */
  static Inventory create(Database database, Clock clock, Duration holdLength, Catalog catalog) {
    return new HoldStore(database.forModule(DATABASE_ROLE), clock, holdLength, catalog);
  }

  /** The seat is held for this fan until {@code expiresAt}. */
  record Held(UUID holdId, Instant expiresAt) {

    /** Refuses a missing field. */
    public Held {
      Objects.requireNonNull(holdId, "holdId");
      Objects.requireNonNull(expiresAt, "expiresAt");
    }
  }

  /** The seat a hold claims, in the catalog's identity. */
  record ClaimedSeat(long eventId, String seatLabel) {

    /** Refuses a missing label. */
    public ClaimedSeat {
      Objects.requireNonNull(seatLabel, "seatLabel");
    }
  }

  /** Why inventory said no; each case says it in words for the fan. */
  sealed interface Refusal {

    /** The refusal in words. */
    String reason();

    /** The catalog offers no such seat. */
    record NoSuchSeat(long eventId, String seatLabel) implements Refusal {
      @Override
      public String reason() {
        return "no seat " + seatLabel + " in event " + eventId;
      }
    }

    /** Another live hold already claims the seat. */
    record SeatClaimed(String seatLabel) implements Refusal {
      @Override
      public String reason() {
        return "seat " + seatLabel + " is already claimed";
      }
    }

    /** No hold with this id belongs to this fan. */
    record NoSuchHold() implements Refusal {
      @Override
      public String reason() {
        return "no such hold for this fan";
      }
    }

    /** The hold exists but cannot be used: it expired, or is in another state. */
    record HoldUnusable(String state) implements Refusal {
      @Override
      public String reason() {
        return "hold is " + state;
      }
    }
  }
}
