package io.plainround.seatwave.modulith.payment.internal;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.modulith.payment.Payments;
import io.plainround.seatwave.modulith.platform.Database;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The payment module's table and its provider calls. The provider here is a stand-in that answers
 * at once; Module 7's real call has a timeout and an unknown outcome, which Module 28's saga
 * handles. Public only so the module's interface can create it; the boundary tests refuse any use
 * of it from another module. Thread-safe if the stand-in's predicate is.
 */
public final class PaymentStore implements Payments {

  private static final String READ_SQL =
      "SELECT state FROM payment.payments WHERE provider_key = ?";
  private static final String RECORD_SQL =
      """
      INSERT INTO payment.payments (provider_key, booking_id, state, amount, currency)
      VALUES (?, ?, ?, ?, ?)
      """;
  private static final String REFUND_SQL =
      "UPDATE payment.payments SET state = 'REFUNDED' WHERE provider_key = ? AND state = 'CAPTURED'";
  private static final String DECLINE_REASON = "card declined by the provider";

  private final Database database;
  private final Predicate<String> declines;

  /** Creates the store with the provider stand-in's rule. */
  public PaymentStore(Database database, Predicate<String> declines) {
    this.database = Objects.requireNonNull(database, "database");
    this.declines = Objects.requireNonNull(declines, "declines");
  }

  @Override
  public Result<Captured, Declined> charge(String providerKey, UUID bookingId, Money amount) {
    Objects.requireNonNull(providerKey, "providerKey");
    Objects.requireNonNull(bookingId, "bookingId");
    Objects.requireNonNull(amount, "amount");
    refuseInsideATransaction("charge");
    // The provider is idempotent by key (Module 7), so asking again after a crash is safe.
    String answer = declines.test(providerKey) ? "DECLINED" : "CAPTURED";
    // Payment records the answer at once, in its own transaction: the truth about money must not
    // wait for another module's transaction to succeed.
    String recorded =
        database.inTransaction(connection -> {
          try (PreparedStatement read = connection.prepareStatement(READ_SQL)) {
            read.setString(1, providerKey);
            try (ResultSet row = read.executeQuery()) {
              if (row.next()) {
                return row.getString(1);
              }
            }
          }
          try (PreparedStatement record = connection.prepareStatement(RECORD_SQL)) {
            record.setString(1, providerKey);
            record.setObject(2, bookingId);
            record.setString(3, answer);
            record.setBigDecimal(4, amount.amount());
            record.setString(5, amount.currency());
            record.executeUpdate();
          }
          return answer;
        });
    // A refunded charge was captured first: the key's answer stays the capture.
    return "DECLINED".equals(recorded)
        ? Result.error(new Declined(providerKey, DECLINE_REASON))
        : Result.success(new Captured(providerKey));
  }

  @Override
  public void refund(String providerKey) {
    Objects.requireNonNull(providerKey, "providerKey");
    refuseInsideATransaction("refund");
    // The stand-in provider refunds at once, by key; zero rows changed is a repeat.
    database.inTransaction(connection -> {
      try (PreparedStatement refund = connection.prepareStatement(REFUND_SQL)) {
        refund.setString(1, providerKey);
        return refund.executeUpdate();
      }
    });
  }

  private void refuseInsideATransaction(String call) {
    if (database.isInTransaction()) {
      // A provider call inside a transaction would hold the caller's row locks for the provider's
      // 2 s p99 (Module 28) and could not be rolled back anyway.
      throw new IllegalStateException(call + " must be called outside any transaction");
    }
  }
}
