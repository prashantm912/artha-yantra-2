package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.golden.GoldenSignalsJson.SignalEvent;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Audit fix replay-legs-silent-index0-fallback: a signal timestamp with no exact 1m bar (the
 * coarse bucket's first minute missing — a routine 1m gap) used to silently become bar 0 — the
 * trade opened at the window start at that bar's price. It now resolves to the first bar at/after
 * the timestamp; a timestamp past the last bar reports -1 so callers drop/force-close explicitly.
 * Exact hits are byte-identical to the old map (the goldens pin that).
 */
class BarIndexResolverTest {

  private static EngineCandle barAt(String iso) {
    return new EngineCandle(
        OffsetDateTime.parse(iso), new BigDecimal("100"), new BigDecimal("101"),
        new BigDecimal("99"), new BigDecimal("100.5"), 10L, null);
  }

  // 09:15..09:17 present, 09:18 MISSING, 09:19..09:20 present
  private static final List<EngineCandle> BARS =
      List.of(
          barAt("2026-07-03T09:15:00+05:30"),
          barAt("2026-07-03T09:16:00+05:30"),
          barAt("2026-07-03T09:17:00+05:30"),
          barAt("2026-07-03T09:19:00+05:30"),
          barAt("2026-07-03T09:20:00+05:30"));

  @Test
  void exactHitsResolveAsBefore() {
    BarIndexResolver r = new BarIndexResolver(BARS);
    assertThat(r.atOrAfter("2026-07-03T09:15+05:30")).isEqualTo(0);
    assertThat(r.atOrAfter("2026-07-03T09:20+05:30")).isEqualTo(4);
  }

  @Test
  void aMissingFirstMinuteResolvesToTheNextBarNotBarZero() {
    BarIndexResolver r = new BarIndexResolver(BARS);
    // the 09:18 coarse-bucket start has no 1m bar — the old map returned 0 here
    assertThat(r.atOrAfter("2026-07-03T09:18:00+05:30")).isEqualTo(3); // the 09:19 bar
  }

  @Test
  void aTimestampPastTheLastBarReportsNoFillBar() {
    BarIndexResolver r = new BarIndexResolver(BARS);
    assertThat(r.atOrAfter("2026-07-03T09:30:00+05:30")).isEqualTo(-1);
  }

  @Test
  void offsetDifferencesNeverMissTheCeiling() {
    // the #214 trap: the exact-string key misses across UTC offsets, but the Instant-keyed
    // ceiling still resolves the same wall-clock moment (03:47Z == 09:17 IST → that very bar)
    BarIndexResolver r = new BarIndexResolver(BARS);
    assertThat(r.atOrAfter("2026-07-03T03:47:00Z")).isEqualTo(2);
  }

  @Test
  void legsPairingDropsAnEntryWithNoFillBarInsteadOfFabricatingBarZero() {
    SignalEvent entry =
        new SignalEvent("2026-07-03T09:25:00+05:30", "NFO", "NIFTY26JULFUT", "LONG", null, null, null);
    SignalEvent exit =
        new SignalEvent("2026-07-03T09:26:00+05:30", "NFO", "NIFTY26JULFUT", "EXIT", null, null, null);

    assertThat(ReplayEngine.legs(List.of(entry, exit), new BarIndexResolver(BARS), BARS.size()))
        .isEmpty();
  }

  @Test
  void legsPairingResolvesAGappedEntryToTheNextBar() {
    SignalEvent entry =
        new SignalEvent("2026-07-03T09:18:00+05:30", "NFO", "NIFTY26JULFUT", "LONG", null, null, null);
    SignalEvent exit =
        new SignalEvent("2026-07-03T09:20:00+05:30", "NFO", "NIFTY26JULFUT", "EXIT", null, null, null);

    var legs = ReplayEngine.legs(List.of(entry, exit), new BarIndexResolver(BARS), BARS.size());

    assertThat(legs).hasSize(1);
    assertThat(legs.get(0).entryIndex()).isEqualTo(3); // the 09:19 bar, NOT bar 0
    assertThat(legs.get(0).exitIndex()).isEqualTo(4);
  }
}
