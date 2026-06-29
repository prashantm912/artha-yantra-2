package in.arthayantra.marketdata.futures.screener;

import in.arthayantra.marketdata.futures.screener.MarketMoversScreener.DailyBar;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient.Bar;
import in.arthayantra.marketdata.upstox.UpstoxFnoMasterClient;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * E1 v2: on-demand daily OHLC+OI for an ARBITRARY stock's FRONT future via Upstox — the data path that
 * widens the §3.3 Market-Movers radar from the captured NIFTY-Bank subset (v1, snapshot-fed) to the full
 * NIFTY-50 WITHOUT any new capture / migration / OOM. For a stock it: (1) resolves the front FUT contract
 * from the instrument master, (2) maps it to the Upstox {@code NSE_FO|<token>} {@code instrument_key} via
 * {@link UpstoxFnoMasterClient#keyFor} (#58 — already indexes every stock FUT, not just indices), (3)
 * fetches the recent daily candles via {@link UpstoxExpiredInstrumentsClient#activeCandles} (#341, OHLCV+OI),
 * and (4) maps each to the screener's {@link DailyBar} (oldest→newest, the last = today). Empty (the stock
 * is simply skipped) when the front future / Upstox key / candles are unavailable — this enrichment never
 * throws into the screener.
 */
@Component
public class UpstoxFnoDailyReader {

  private static final Logger log = LoggerFactory.getLogger(UpstoxFnoDailyReader.class);

  private final InstrumentRepository instruments;
  // OPTIONAL: the two Upstox wire clients are @ConditionalOnProperty(artha.upstox.analytics.enabled) —
  // absent when Upstox analytics is off, so they ride as ObjectProviders (this reader ALWAYS wires; it
  // degrades to empty when Upstox isn't configured rather than breaking the futures-controller chain).
  private final ObjectProvider<UpstoxFnoMasterClient> fnoMaster;
  private final ObjectProvider<UpstoxExpiredInstrumentsClient> upstox;
  private final Clock clock;

  /** True when the (conditional) Upstox analytics clients are both present — else the v2 radar is off. */
  public boolean upstoxAvailable() {
    return fnoMaster.getIfAvailable() != null && upstox.getIfAvailable() != null;
  }

  /** Wires the instrument master + the (optional) Upstox wire clients. */
  public UpstoxFnoDailyReader(
      InstrumentRepository instruments,
      ObjectProvider<UpstoxFnoMasterClient> fnoMaster,
      ObjectProvider<UpstoxExpiredInstrumentsClient> upstox,
      Clock clock) {
    this.instruments = instruments;
    this.fnoMaster = fnoMaster;
    this.upstox = upstox;
    this.clock = clock;
  }

  /**
   * The stock's recent daily bars (oldest→newest) from its front future, or empty when unresolvable.
   * {@code from}/{@code to} bound the daily window (≈25-30 sessions for the breakout + RSI lookback).
   */
  public List<DailyBar> dailyBars(String underlying, LocalDate from, LocalDate to) {
    UpstoxFnoMasterClient master = fnoMaster.getIfAvailable();
    UpstoxExpiredInstrumentsClient upstoxClient = upstox.getIfAvailable();
    if (master == null || upstoxClient == null) {
      return List.of(); // Upstox analytics disabled — the v2 radar is simply unavailable
    }
    LocalDate today = LocalDate.now(clock);
    Instrument front =
        instruments.futures(underlying).stream()
            .filter(i -> i.expiry() != null && !i.expiry().isBefore(today))
            .min(Comparator.comparing(Instrument::expiry))
            .orElse(null);
    if (front == null) {
      return List.of();
    }
    String key = master.keyFor("NFO", underlying, "FUT", front.expiry(), null);
    if (key == null) {
      log.info("dailyBars {}: no Upstox key for front {} (expiry {})",
          underlying, front.tradingsymbol(), front.expiry());
      return List.of();
    }
    List<Bar> bars;
    try {
      bars = upstoxClient.activeCandles(key, "day", from, to);
    } catch (RuntimeException e) {
      log.warn("upstox daily candles failed for {} ({}): {}", underlying, key, e.getMessage());
      return List.of();
    }
    List<DailyBar> out = new ArrayList<>();
    bars.stream()
        .sorted(Comparator.comparing(Bar::bucket)) // Upstox returns newest-first; the screener needs oldest→newest
        .forEach(b -> out.add(new DailyBar(b.open(), b.high(), b.low(), b.close(), b.volume(), b.oi())));
    return out;
  }
}
