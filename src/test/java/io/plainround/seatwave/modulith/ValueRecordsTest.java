package io.plainround.seatwave.modulith;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.modulith.catalog.Catalog.SeatOffer;
import io.plainround.seatwave.modulith.payment.Payments.Money;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The values that cross module boundaries cannot exist in an invalid state. */
class ValueRecordsTest {

  private static final Instant SHOW = Instant.parse("2026-12-08T20:20:51.783Z");

  @Test
  void moneyRefusesAZeroAmount() {
    assertThatThrownBy(() -> new Money(BigDecimal.ZERO, "USD"))
        .hasMessage("amount must be positive: 0");
  }

  @Test
  void moneyRefusesAMalformedCurrency() {
    assertThatThrownBy(() -> new Money(BigDecimal.TEN, "usd"))
        .hasMessage("currency must be three capital letters: usd");
  }

  @Test
  void seatOfferRefusesANegativePrice() {
    assertThatThrownBy(() -> new SeatOffer(new BigDecimal("-1"), "USD", SHOW))
        .hasMessage("price must not be negative: -1");
  }

  @Test
  void seatOfferRefusesAMalformedCurrency() {
    assertThatThrownBy(() -> new SeatOffer(BigDecimal.ONE, "US", SHOW))
        .hasMessage("currency must be three capital letters: US");
  }
}
