package in.arthayantra.marketdata.openalgo.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** OpenAlgo {@code DDMMMYY} expiry-token grammar: format, parse, round-trip, case-insensitivity. */
class OpenAlgoSymbolsTest {

  @Test
  void formatsExpiryAsUppercaseDdMmmYy() {
    assertThat(OpenAlgoSymbols.expiryToken(LocalDate.of(2026, 12, 30))).isEqualTo("30DEC26");
    assertThat(OpenAlgoSymbols.expiryToken(LocalDate.of(2026, 1, 2))).isEqualTo("02JAN26");
    assertThat(OpenAlgoSymbols.expiryToken(LocalDate.of(2025, 7, 31))).isEqualTo("31JUL25");
  }

  @Test
  void parsesDdMmmYyBackToDate() {
    assertThat(OpenAlgoSymbols.parseExpiry("30DEC26")).isEqualTo(LocalDate.of(2026, 12, 30));
    assertThat(OpenAlgoSymbols.parseExpiry("02JAN26")).isEqualTo(LocalDate.of(2026, 1, 2));
    // case-insensitive
    assertThat(OpenAlgoSymbols.parseExpiry("30dec26")).isEqualTo(LocalDate.of(2026, 12, 30));
  }

  @Test
  void roundTripsEveryMonth() {
    for (int month = 1; month <= 12; month++) {
      LocalDate expiry = LocalDate.of(2027, month, 15);
      assertThat(OpenAlgoSymbols.parseExpiry(OpenAlgoSymbols.expiryToken(expiry))).isEqualTo(expiry);
    }
  }
}
