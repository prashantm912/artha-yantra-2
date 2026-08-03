package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.options.OiInterval;
import in.arthayantra.marketdata.options.analytics.OptionsSnapshotReader.OptionEodRow;
import in.arthayantra.marketdata.options.analytics.OptionsSnapshotReader.PerStrikeSessionStat;
import in.arthayantra.marketdata.options.analytics.OptionsSnapshotReader.StrikePoint;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * A read facade over {@link OptionsSnapshotReader} that, for a FULLY-PAST request with no captured
 * {@code options_chain_snapshots}, falls back to the candle-derived chain ({@link
 * CandleDerivedChainReader}) — so the whole oipulse OI-page suite ({@code OptionsAnalyticsController})
 * works on history, not just live. Same method shapes the controller already calls, so it's a drop-in
 * field swap.
 *
 * <p>Parity: the snapshot reader is ALWAYS queried first; the derived path is reachable ONLY when (a)
 * the snapshot result is empty, (b) the request is fully historical (a {@code date} strictly before
 * today IST, or a {@code [from,to)} window at/before today's IST start), and (c) the index has a
 * backfilled expired-contract roster. LIVE reads pass {@code date == null} (or a today-window), so the
 * candle path is structurally unreachable for live — existing behaviour is byte-unchanged. The user's
 * expiry is passed straight through to the derived twins (the FE supplies the historical expiry);
 * iv/greeks/spot stay null on derived rows (candles carry no solver inputs).
 *
 * <p>{@code sessionStats} forwards straight to snapshots for now (its candle-derived twin — prev-close
 * via the prior trading day — lands in the follow-up).
 */
@Component
public class HistoricalOiReader {

  private final OptionsSnapshotReader snap;
  private final CandleDerivedChainReader derived;
  private final Clock clock;

  public HistoricalOiReader(
      OptionsSnapshotReader snap, CandleDerivedChainReader derived, Clock clock) {
    this.snap = snap;
    this.derived = derived;
    this.clock = clock;
  }

  private boolean historicalDate(LocalDate date) {
    return date != null && date.isBefore(LocalDate.ofInstant(clock.instant(), Ist.ZONE));
  }

  private boolean historicalWindow(OffsetDateTime to) {
    OffsetDateTime todayStart =
        LocalDate.ofInstant(clock.instant(), Ist.ZONE).atStartOfDay().atOffset(Ist.OFFSET);
    return !to.isAfter(todayStart);
  }

  // ── live overloads (no date / today window) — always native, never derive ──────────────────

  public List<StrikePoint> latest(String name, LocalDate expiry, OiInterval interval) {
    return snap.latest(name, expiry, interval);
  }

  public List<StrikePoint> latestPair(String name, LocalDate expiry, OiInterval interval) {
    return snap.latestPair(name, expiry, interval);
  }

  // ── date/window-scoped reads — snapshot first, candle-derived fallback on fully-past empties ──

  public List<StrikePoint> latest(String name, LocalDate expiry, OiInterval interval, LocalDate date) {
    List<StrikePoint> live = snap.latest(name, expiry, interval, date);
    if (!live.isEmpty() || !historicalDate(date)) {
      return live;
    }
    String eu = CandleDerivedChainReader.expiredUnderlying(name);
    return eu == null ? live : derived.latest(eu, expiry, interval, date);
  }

  public List<StrikePoint> latestPair(
      String name, LocalDate expiry, OiInterval interval, LocalDate date) {
    List<StrikePoint> live = snap.latestPair(name, expiry, interval, date);
    if (!live.isEmpty() || !historicalDate(date)) {
      return live;
    }
    String eu = CandleDerivedChainReader.expiredUnderlying(name);
    return eu == null ? live : derived.latestPair(eu, expiry, interval, date);
  }

  public List<StrikePoint> series(
      String name, LocalDate expiry, OiInterval interval, OffsetDateTime from, OffsetDateTime to) {
    List<StrikePoint> live = snap.series(name, expiry, interval, from, to);
    if (!live.isEmpty() || !historicalWindow(to)) {
      return live;
    }
    String eu = CandleDerivedChainReader.expiredUnderlying(name);
    return eu == null ? live : derived.series(eu, expiry, interval, from, to);
  }

  public List<StrikePoint> strikeSeries(
      String name,
      LocalDate expiry,
      BigDecimal strike,
      OiInterval interval,
      OffsetDateTime from,
      OffsetDateTime to) {
    List<StrikePoint> live = snap.strikeSeries(name, expiry, strike, interval, from, to);
    if (!live.isEmpty() || !historicalWindow(to)) {
      return live;
    }
    String eu = CandleDerivedChainReader.expiredUnderlying(name);
    return eu == null ? live : derived.strikeSeries(eu, expiry, strike, interval, from, to);
  }

  public List<OptionEodRow> eodSeries(
      String name, LocalDate expiry, OffsetDateTime from, OffsetDateTime to) {
    List<OptionEodRow> live = snap.eodSeries(name, expiry, from, to);
    if (!live.isEmpty() || !historicalWindow(to)) {
      return live;
    }
    String eu = CandleDerivedChainReader.expiredUnderlying(name);
    return eu == null ? live : derived.eodSeries(eu, expiry, from, to);
  }

  /**
   * True iff [{@code from}, {@code to}) is backed by at least one LIVE-CAPTURED snapshot row —
   * {@link OptionsSnapshotReader#hasCapturedRows}, straight through. Deliberately NOT facaded onto
   * the derived reader: the derived path writes nothing, so a window it served has no snapshot row
   * at all and this correctly answers false.
   */
  public boolean hasCapturedRows(
      String name, LocalDate expiry, OffsetDateTime from, OffsetDateTime to) {
    return snap.hasCapturedRows(name, expiry, from, to);
  }

  public List<PerStrikeSessionStat> sessionStats(
      String name, LocalDate expiry, LocalDate session, int intervalMinutes) {
    List<PerStrikeSessionStat> live = snap.sessionStats(name, expiry, session, intervalMinutes);
    if (!live.isEmpty() || !historicalDate(session)) {
      return live;
    }
    String eu = CandleDerivedChainReader.expiredUnderlying(name);
    return eu == null ? live : derived.sessionStats(eu, expiry, session, intervalMinutes);
  }
}
