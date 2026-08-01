package in.arthayantra.marketdata.upstox;

import in.arthayantra.marketdata.kite.GlobalQuoteSource;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway.Quote;
import in.arthayantra.marketdata.upstox.wire.UpstoxMarketQuote;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Upstox adapter for the {@link GlobalQuoteSource} port — the SECOND implementation, wired when
 * {@code artha.upstox.global-quotes-enabled=true} (default off, mutually exclusive with the OpenAlgo
 * one; see {@code GlobalQuoteSourceExclusivityGuard}).
 *
 * <p>It reuses the ALREADY-LIVE world-indices feed rather than opening a second Upstox coupling: the
 * same {@link UpstoxGlobalInstrumentsClient} that serves {@code GET /api/v1/market/world-indices} is
 * asked for a ONE-key batch, so the Dow rides the shared analytics token + the shared
 * {@link UpstoxRateLimiter} budget. The canonical {@code GLOBAL_INDEX@DOWJONES} key is translated at
 * the wire edge by {@link UpstoxGlobalInstrumentKeys} ({@code → GLOBAL_INDEX|^DJI}).
 *
 * <p>{@code prevClose} lands in the {@link Quote.Ohlc} close slot — the shape {@code
 * ConnectingDotsService.dowFactor} and {@code MarketSurfaceController.dow()} read — and is derived
 * {@code ltp − net_change} exactly as {@link UpstoxGlobalInstrumentsClient} derives it for the World
 * Indices page (the quote {@code ohlc.close} is the prev-day close but less precise), so both
 * surfaces agree on the number.
 *
 * <p>Best-effort like its OpenAlgo sibling: every failure returns empty so the Dow factor degrades to
 * Neutral rather than failing the whole matrix — but NEVER silently. Each degradation logs WARN and
 * increments {@link GlobalQuoteSource#DEGRADED_METRIC} tagged {@code source=upstox} plus a reason, so
 * "the Dow dot is dark" is observable instead of being read as a genuine Neutral.
 */
public final class UpstoxGlobalQuoteClient implements GlobalQuoteSource {

  private static final Logger log = LoggerFactory.getLogger(UpstoxGlobalQuoteClient.class);

  private final UpstoxGlobalInstrumentsClient delegate;
  private final MeterRegistry meterRegistry;

  /** Wraps the live world-indices client; {@code meterRegistry} carries the degradation counter. */
  public UpstoxGlobalQuoteClient(
      UpstoxGlobalInstrumentsClient delegate, MeterRegistry meterRegistry) {
    this.delegate = delegate;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public Optional<Quote> latest(InstrumentKey key) {
    String upstoxKey = UpstoxGlobalInstrumentKeys.key(key);
    if (upstoxKey == null) {
      return degraded(key, "unmapped", null);
    }
    UpstoxMarketQuote.Tick tick;
    try {
      tick = delegate.quote(List.of(upstoxKey)).get(upstoxKey);
    } catch (RuntimeException unavailable) {
      return degraded(key, "error", unavailable);
    }
    if (tick == null || tick.lastPrice() == null) {
      return degraded(key, "absent", null);
    }
    return Optional.of(toQuote(key, tick));
  }

  /** Maps an Upstox tick onto the domain quote, prev close in the OHLC close slot (LTP-only globals). */
  private static Quote toQuote(InstrumentKey key, UpstoxMarketQuote.Tick tick) {
    UpstoxMarketQuote.Ohlc ohlc = tick.ohlc();
    BigDecimal ltp = tick.lastPrice();
    BigDecimal netChange = tick.netChange();
    BigDecimal prevClose =
        netChange != null ? ltp.subtract(netChange) : (ohlc == null ? null : ohlc.close());
    return new Quote(
        key,
        ltp,
        null,
        null,
        null,
        null,
        new Quote.Ohlc(
            ohlc == null ? null : ohlc.open(),
            ohlc == null ? null : ohlc.high(),
            ohlc == null ? null : ohlc.low(),
            prevClose),
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  /** Counts + logs one degradation-to-Neutral, then returns the fail-soft empty. */
  private Optional<Quote> degraded(InstrumentKey key, String reason, RuntimeException cause) {
    meterRegistry.counter(DEGRADED_METRIC, "source", "upstox", "reason", reason).increment();
    log.warn(
        "global quote degraded to neutral: key={} source=upstox reason={}{}",
        key == null ? "null" : key.canonical(),
        reason,
        cause == null ? "" : " cause=" + cause);
    return Optional.empty();
  }
}
