package in.arthayantra.backtest.replay.options;

import in.arthayantra.backtest.replay.CandleReader;
import in.arthayantra.backtest.replay.options.OptionContractSelector.OptionContract;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Reads an option contract's PREMIUM series — its own 1-minute {@code candles} closes (the backfilled
 * expired-option bars, {@code source='BACKFILL'}) — for premium-as-primary replay (Part 2). This is the
 * tradeable price the {@code ReplayEngine} fills + marks against for an options strategy, in place of
 * the underlying candle close ({@link PremiumSource#CANDLE_1M}).
 *
 * <p>The series is a {@link NavigableMap} so the engine can align it to the underlying signal bars:
 * an option may not trade on every minute the index does, so {@link #premiumAt} carries the LAST known
 * premium forward (floor lookup) rather than inventing a price — the standard mark-to-last for an
 * illiquid leg.
 */
@Component
public class CandlePremiumReader {

  private final CandleReader candleReader;

  public CandlePremiumReader(CandleReader candleReader) {
    this.candleReader = candleReader;
  }

  /**
   * The contract's premium (1m close) series over {@code [from, to)}, keyed by bar start. Empty when
   * the backfilled candles don't cover the contract (the caller then skips/refuses the leg rather than
   * fabricating premiums).
   */
  public NavigableMap<OffsetDateTime, BigDecimal> premiumSeries(
      OptionContract contract, OffsetDateTime from, OffsetDateTime to) {
    List<EngineCandle> bars =
        candleReader.read(contract.exchange(), contract.tradingsymbol(), "1m", from, to);
    NavigableMap<OffsetDateTime, BigDecimal> series = new TreeMap<>();
    for (EngineCandle bar : bars) {
      series.put(bar.bucketStart(), bar.close());
    }
    return series;
  }

  /**
   * The premium AT {@code ts} for a series — the bar at {@code ts}, else the last premium on/before it
   * (carry-forward for a minute the option didn't trade). Null when {@code ts} precedes the first bar
   * (no premium has traded yet — the leg can't be priced).
   */
  public static BigDecimal premiumAt(
      NavigableMap<OffsetDateTime, BigDecimal> series, OffsetDateTime ts) {
    var entry = series.floorEntry(ts);
    return entry == null ? null : entry.getValue();
  }
}
