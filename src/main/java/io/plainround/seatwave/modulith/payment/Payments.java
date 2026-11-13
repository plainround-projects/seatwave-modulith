package io.plainround.seatwave.modulith.payment;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.modulith.payment.internal.PaymentStore;
import io.plainround.seatwave.modulith.platform.Database;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The payment module's public interface: taking money once per provider key, and giving it back. It
 * owns the schema {@code payment}. Both methods call the payment provider, so they must be called
 * outside any transaction (code guidelines, rule 69): each records the provider's answer in its
 * own. Safe for concurrent use.
 */
public interface Payments {

  /** The database role the module's statements run in; it is granted the schema payment only. */
  String DATABASE_ROLE = "payment_module";

  /**
   * Charges once under the key; a repeat with the same key returns the first answer.
   *
   * @return the capture, or the provider's decline
   * @throws IllegalStateException if called inside a transaction
   */
  Result<Captured, Declined> charge(String providerKey, UUID bookingId, Money amount);

  /**
   * Returns a captured charge to the fan; repeating it changes nothing.
   *
   * @throws IllegalStateException if called inside a transaction
   */
  void refund(String providerKey);

  /**
   * Creates the payment module with the lab's provider stand-in.
   *
   * @param declines which provider keys the stand-in declines, so a lab can show the compensation
   */
  static Payments create(Database database, Predicate<String> declines) {
    return new PaymentStore(database.forModule(DATABASE_ROLE), declines);
  }

  /**
   * An amount in payment's own terms; the booking module translates the catalog's price into it, so
   * payment never depends on the catalog.
   */
  record Money(BigDecimal amount, String currency) {

    private static final String CURRENCY_FORMAT = "[A-Z]{3}";

    /** Refuses a zero or negative amount and a malformed currency. */
    public Money {
      Objects.requireNonNull(amount, "amount");
      Objects.requireNonNull(currency, "currency");
      if (amount.signum() <= 0) {
        throw new IllegalArgumentException("amount must be positive: " + amount);
      }
      if (!currency.matches(CURRENCY_FORMAT)) {
        throw new IllegalArgumentException("currency must be three capital letters: " + currency);
      }
    }
  }

  /** The money was taken under this key. */
  record Captured(String providerKey) {

    /** Refuses a missing key. */
    public Captured {
      Objects.requireNonNull(providerKey, "providerKey");
    }
  }

  /** The provider refused; no money moved. A record, so the error type is closed. */
  record Declined(String providerKey, String reason) {

    /** Refuses a missing field. */
    public Declined {
      Objects.requireNonNull(providerKey, "providerKey");
      Objects.requireNonNull(reason, "reason");
    }
  }
}
