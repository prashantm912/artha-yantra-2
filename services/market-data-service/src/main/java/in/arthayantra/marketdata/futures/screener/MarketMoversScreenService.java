package in.arthayantra.marketdata.futures.screener;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.futures.analytics.FuturesSnapshotReader;
import in.arthayantra.marketdata.futures.analytics.FuturesSnapshotReader.EodRow;
import in.arthayantra.marketdata.futures.analytics.FuturesSnapshotReader.FutPoint;
import in.arthayantra.marketdata.futures.screener.MarketMoversScreener.DailyBar;
import in.arthayantra.marketdata.futures.screener.MarketMoversScreener.ScreenerRow;
import in.arthayantra.marketdata.options.OiInterval;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * §3.3 Market-Movers WIRING (the WHICH-stock pick; the engine entry_rules do the WHEN). Reads the
 * captured stock-future snapshots for the radar set and reuses the pure {@link MarketMoversScreener}
 * core to grade each radar stock's high-probability LONG / SHORT candidates:
 *
 * <ul>
 *   <li>TODAY's bar = the radar stock's FRONT future from the newest captured bucket
 *       ({@link FuturesSnapshotReader#latestPairAll}) — {@code dayOpen/dayHigh/dayLow/ltp/oi/volume}.
 *   <li>HISTORY = the same front contract's per-IST-day EOD rollup over the prior ~25 sessions
 *       ({@link FuturesSnapshotReader#eod}); the 8/9-day breakout + daily-RSI fall out of the core.
 * </ul>
 *
 * <p>v1 radar = the captured NIFTY-Bank constituent stock futures (the bank-stocks config); the deck
 * filters Market-Movers to "Nifty 50 AND Nifty Bank", so a Nifty-Bank subset is faithful, not a
 * degradation. Widening to the full Nifty-50 needs the Upstox active F&O instrument-key lookup (v2).
 */
@Service
public class MarketMoversScreenService {

  /** History depth for the rolling 8/9-day breakout + daily-RSI (calendar days; gaps skip weekends). */
  static final int HISTORY_DAYS = 25;

  private final FuturesSnapshotReader reader;
  private final UpstoxFnoDailyReader upstoxReader;
  private final java.time.Clock clock;

  public MarketMoversScreenService(
      FuturesSnapshotReader reader, UpstoxFnoDailyReader upstoxReader, java.time.Clock clock) {
    this.reader = reader;
    this.upstoxReader = upstoxReader;
    this.clock = clock;
  }

  /**
   * E1 v2: grade an arbitrary radar (e.g. the full NIFTY-50) from ON-DEMAND Upstox daily candles instead
   * of the captured snapshots — same {@link MarketMoversScreener} core, so the WHICH-stock grading is
   * identical; only the data SOURCE differs (no capture / migration / OOM). {@code null} when no radar
   * stock yields ≥2 daily bars (the controller 422s). Best-effort per stock (an unresolvable stock is
   * skipped, never throws).
   */
  public Screen screenUpstox(List<String> radar) {
    LocalDate today = LocalDate.now(clock);
    Map<String, List<DailyBar>> byStock = new LinkedHashMap<>();
    for (String stock : radar) {
      List<DailyBar> bars = upstoxReader.dailyBars(stock, today.minusDays(HISTORY_DAYS), today);
      if (bars.size() >= 2) {
        byStock.put(stock, bars);
      }
    }
    if (byStock.isEmpty()) {
      return null;
    }
    MarketMoversScreener.Screen s = MarketMoversScreener.screen(byStock);
    OffsetDateTime asOf = OffsetDateTime.now(clock).withOffsetSameInstant(Ist.OFFSET);
    return new Screen(s.longCandidates(), s.shortCandidates(), asOf);
  }

  /** The ranked long / short candidate lists + the snapshot bucket they were graded from. */
  public record Screen(
      List<ScreenerRow> longCandidates, List<ScreenerRow> shortCandidates, OffsetDateTime asOf) {}

  /**
   * Grade the radar set from the captured snapshots; {@code null} when NO radar contract has a bucket
   * (the controller 422s). {@code date} non-null = history mode (that IST day), null = live (newest).
   */
  public Screen screen(List<String> radar, OiInterval interval, LocalDate date) {
    List<FutPoint> pair = reader.latestPairAll(radar, interval, date);
    if (pair.isEmpty()) {
      return null;
    }
    OffsetDateTime newest =
        pair.stream().map(FutPoint::bucket).max(Comparator.naturalOrder()).orElseThrow();
    LocalDate today = newest.atZoneSameInstant(Ist.ZONE).toLocalDate();

    // Front future per radar underlying from the newest bucket (nearest expiry).
    Map<String, FutPoint> frontByUnderlying = new LinkedHashMap<>();
    for (FutPoint p : pair) {
      if (p.bucket().equals(newest)) {
        frontByUnderlying.merge(p.underlying(), p, MarketMoversScreenService::nearerExpiry);
      }
    }

    Map<String, List<DailyBar>> byStock = new LinkedHashMap<>();
    for (Map.Entry<String, FutPoint> e : frontByUnderlying.entrySet()) {
      FutPoint front = e.getValue();
      DailyBar todayBar = todayBar(front);
      if (todayBar == null) {
        continue; // OHLC not captured yet -> can't grade OH/OL or breakout
      }
      List<DailyBar> bars = new ArrayList<>();
      for (EodRow r : reader.eod(e.getKey(), today.minusDays(HISTORY_DAYS), today.minusDays(1))) {
        if (r.tradingsymbol().equals(front.tradingsymbol()) && r.open() != null) {
          bars.add(new DailyBar(r.open(), r.high(), r.low(), r.close(), r.volume(), r.oiClose()));
        }
      }
      bars.add(todayBar);
      byStock.put(e.getKey(), bars);
    }

    MarketMoversScreener.Screen s = MarketMoversScreener.screen(byStock);
    return new Screen(s.longCandidates(), s.shortCandidates(), newest);
  }

  /** Today's daily bar from the front future's captured day-OHLC; null if the OHLC is absent. */
  private static DailyBar todayBar(FutPoint f) {
    if (f.dayOpen() == null || f.dayHigh() == null || f.dayLow() == null || f.ltp() == null) {
      return null;
    }
    long vol = f.volume() == null ? 0 : f.volume();
    return new DailyBar(f.dayOpen(), f.dayHigh(), f.dayLow(), f.ltp(), vol, f.oi());
  }

  /** The nearer-expiry of two contracts (nulls sort last) — the front future. */
  private static FutPoint nearerExpiry(FutPoint a, FutPoint b) {
    if (a.expiry() == null) {
      return b;
    }
    if (b.expiry() == null) {
      return a;
    }
    return a.expiry().isAfter(b.expiry()) ? b : a;
  }
}
