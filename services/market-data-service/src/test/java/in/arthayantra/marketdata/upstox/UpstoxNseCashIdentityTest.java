package in.arthayantra.marketdata.upstox;

import static in.arthayantra.marketdata.upstox.UpstoxFnoMasterClient.kiteTradingsymbol;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H26 U-A2 — the NSE cash symbol derivation.
 *
 * <p>⚠️ The cases below are not invented. They are drawn from the live master and our own
 * `instruments` table on 2026-09-02, where this rule was measured at 100.00% over 9,694 rows with
 * ZERO unmatched — receipt:
 * {@code docs/signal-analysis/2026-09-02-h26-ua2-identity-join-measurement.md}. Using real pairs
 * matters here: a hand-invented fixture would have encoded the convention I *expected* rather than
 * the one the exchanges actually use, and the whole point of this unit is that the expected one
 * (a plain symbol match) is wrong for ~73% of NSE equities.
 */
class UpstoxNseCashIdentityTest {

  @Test
  @DisplayName("EQ series keeps the bare symbol")
  void eqSeriesIsBare() {
    assertThat(kiteTradingsymbol("RELIANCE", "EQ")).isEqualTo("RELIANCE");
  }

  @Test
  @DisplayName("BE series takes the suffix — the H29/H36 twin, as one instance of a wider rule")
  void beSeriesIsSuffixed() {
    // ⚠️ This is the case two ledger rows were opened for. It is not special: see the SG/ST cases
    // below, which follow the identical rule and were simply never noticed.
    assertThat(kiteTradingsymbol("DIACABS", "BE")).isEqualTo("DIACABS-BE");
    assertThat(kiteTradingsymbol("MENONBE", "BE")).isEqualTo("MENONBE-BE");
  }

  @Test
  @DisplayName("SG state-development-loan rows take the suffix — the largest class by count")
  void sgSeriesIsSuffixed() {
    // 4,307 of the 7,043 suffixed rows in the 2026-09-02 measurement.
    assertThat(kiteTradingsymbol("749RJ35", "SG")).isEqualTo("749RJ35-SG");
    assertThat(kiteTradingsymbol("645BR27", "SG")).isEqualTo("645BR27-SG");
  }

  @Test
  @DisplayName("ST and other series follow the same rule")
  void otherSeriesAreSuffixed() {
    assertThat(kiteTradingsymbol("KCK", "ST")).isEqualTo("KCK-ST");
    assertThat(kiteTradingsymbol("SOMESYM", "SM")).isEqualTo("SOMESYM-SM");
    assertThat(kiteTradingsymbol("SOMESYM", "GS")).isEqualTo("SOMESYM-GS");
    assertThat(kiteTradingsymbol("SOMESYM", "N0")).isEqualTo("SOMESYM-N0");
  }

  @Test
  @DisplayName("an unknown series still suffixes rather than guessing")
  void unknownSeriesStillSuffixes() {
    // ⚠️ The rule is EQ-vs-everything-else, not a whitelist. A whitelist would silently emit a BARE
    // symbol for a series NSE introduces later — a wrong symbol that looks plausible, which is the
    // failure mode that produces a duplicate row rather than an error. Suffixing an unknown series
    // is wrong LOUDLY: it will simply not match, and the A2-3 diff reports it.
    assertThat(kiteTradingsymbol("NEWTHING", "ZZ")).isEqualTo("NEWTHING-ZZ");
  }
}
