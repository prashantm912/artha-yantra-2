package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.options.OiInterval;
import in.arthayantra.marketdata.options.analytics.OptionsSnapshotReader.StrikePoint;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The historical-fallback gate: the candle-derived path fires ONLY when the snapshot read is empty
 * AND the request is fully past AND the index is backfilled — live reads (null/today) are always the
 * native snapshot result, structurally unable to reach the derived path.
 */
class HistoricalOiReaderTest {

  // fixed "now" → today IST = 2026-06-25; a past date is 2026-04-09.
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-25T06:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate EXPIRY = LocalDate.of(2026, 4, 9);
  private static final LocalDate PAST = LocalDate.of(2026, 4, 9);
  private static final LocalDate TODAY = LocalDate.of(2026, 6, 25);

  private static StrikePoint pt(long oi) {
    return new StrikePoint(
        OffsetDateTime.parse("2026-04-09T09:20:00+05:30"), new BigDecimal("23900"), "CE", null, oi,
        null, null, null, null);
  }

  @Test
  void derivesWhenSnapshotEmptyAndDateFullyPast() {
    OptionsSnapshotReader snap = mock(OptionsSnapshotReader.class);
    CandleDerivedChainReader derived = mock(CandleDerivedChainReader.class);
    when(snap.latest(eq("NIFTY 50"), eq(EXPIRY), eq(OiInterval.M5), eq(PAST))).thenReturn(List.of());
    when(derived.latest(eq("NIFTY"), eq(EXPIRY), eq(OiInterval.M5), eq(PAST)))
        .thenReturn(List.of(pt(1500)));

    HistoricalOiReader reader = new HistoricalOiReader(snap, derived, CLOCK);
    assertThat(reader.latest("NIFTY 50", EXPIRY, OiInterval.M5, PAST)).hasSize(1);
    verify(derived).latest(eq("NIFTY"), eq(EXPIRY), eq(OiInterval.M5), eq(PAST));
  }

  @Test
  void neverDerivesWhenSnapshotHasData() {
    OptionsSnapshotReader snap = mock(OptionsSnapshotReader.class);
    CandleDerivedChainReader derived = mock(CandleDerivedChainReader.class);
    when(snap.latest(any(), any(), any(), any())).thenReturn(List.of(pt(2000)));

    HistoricalOiReader reader = new HistoricalOiReader(snap, derived, CLOCK);
    assertThat(reader.latest("NIFTY 50", EXPIRY, OiInterval.M5, PAST)).containsExactly(pt(2000));
    verify(derived, never()).latest(any(), any(), any(), any());
  }

  @Test
  void neverDerivesForLiveOrTodayOrUnbackfilledIndex() {
    OptionsSnapshotReader snap = mock(OptionsSnapshotReader.class);
    CandleDerivedChainReader derived = mock(CandleDerivedChainReader.class);
    when(snap.latest(any(), any(), any(), any())).thenReturn(List.of());

    HistoricalOiReader reader = new HistoricalOiReader(snap, derived, CLOCK);
    reader.latest("NIFTY 50", EXPIRY, OiInterval.M5, null); // live (no date)
    reader.latest("NIFTY 50", EXPIRY, OiInterval.M5, TODAY); // today → not historical
    reader.latest("RANDOMIDX", EXPIRY, OiInterval.M5, PAST); // no expired roster
    verify(derived, never()).latest(any(), any(), any(), any());
  }

  @Test
  void seriesGatesOnFullyHistoricalWindow() {
    OptionsSnapshotReader snap = mock(OptionsSnapshotReader.class);
    CandleDerivedChainReader derived = mock(CandleDerivedChainReader.class);
    OffsetDateTime from = OffsetDateTime.parse("2026-04-09T00:00:00+05:30");
    OffsetDateTime to = OffsetDateTime.parse("2026-04-10T00:00:00+05:30"); // fully past
    when(snap.series(any(), any(), any(), any(), any())).thenReturn(List.of());
    when(derived.series(eq("NIFTY"), eq(EXPIRY), eq(OiInterval.M5), eq(from), eq(to)))
        .thenReturn(List.of(pt(900)));

    HistoricalOiReader reader = new HistoricalOiReader(snap, derived, CLOCK);
    assertThat(reader.series("NIFTY 50", EXPIRY, OiInterval.M5, from, to)).hasSize(1);
    verify(derived).series(eq("NIFTY"), eq(EXPIRY), eq(OiInterval.M5), eq(from), eq(to));
  }
}
