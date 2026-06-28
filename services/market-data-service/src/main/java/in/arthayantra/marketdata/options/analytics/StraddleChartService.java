package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleQueryService;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.options.OptionsChainService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * oipulse "Straddle Chart" (plan §20.7.6) — the combined CE+PE premium candle series for one
 * (strike, expiry) over a session. Faithful to the source: per interval the candle is the SUM of the
 * two legs' OHLC ({@code O=CE.open+PE.open}, … {@code C=CE.close+PE.close}), plus each leg's close and
 * the summed volume; the FE overlays VWAP / 20-EMA / Call+Put lines (those are pure client maths).
 *
 * <p>No new capture pipeline: each leg's intraday OHLC is read cache-first at {@code 1m} via the
 * generic {@link CandleQueryService} (it fetches the missing 1m bars from broker historical on
 * demand) and aggregated here to the requested interval. The oipulse interval set (1/3/5/10/15/30/60
 * min) is wider than {@code OiInterval}, so this takes raw minutes and buckets session-aligned to the
 * 09:15 IST open. A strangle is the same shape with {@code callStrike != putStrike}.
 */
@Service
public class StraddleChartService {

  /** The NSE/BSE cash-session open — straddle buckets align here (faithful to oipulse's 09:18/09:21). */
  private static final LocalTime SESSION_OPEN = LocalTime.of(9, 15);

  /** The oipulse interval set in minutes (adds 1-min for scalping + 10-min vs the OI pages). */
  private static final Set<Integer> ALLOWED_MINUTES = Set.of(1, 3, 5, 10, 15, 30, 60);

  private static final BigDecimal THREE = BigDecimal.valueOf(3);

  /**
   * Siva #11 long-straddle SL buffer (owner S24 ruling): the combined-premium stop sits {@value} points
   * BELOW the combined-premium VWAP ("Long SL = below the (combined) VWAP", §3.11). The straddle is
   * LIVE-managed — this surfaces the authoritative SL level so the operator squares off on the break.
   */
  private static final BigDecimal SL_BUFFER_POINTS = new BigDecimal("15");

  /** One interval's combined straddle candle: summed OHLC + each leg's close + summed volume. */
  public record StraddleCandle(
      OffsetDateTime time,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal close,
      BigDecimal ceClose,
      BigDecimal peClose,
      long volume) {}

  /**
   * The straddle/strangle chart payload: the header strip + the per-interval combined candles, plus the
   * authoritative combined-premium session VWAP and the long-straddle SL level ({@code combinedVwap −
   * slBufferPoints}) the LIVE exit is managed against (§3.11). {@code combinedVwap}/{@code slLevel} are
   * null on an empty session.
   */
  public record StraddleChart(
      String underlying,
      LocalDate expiry,
      BigDecimal callStrike,
      BigDecimal putStrike,
      String interval,
      BigDecimal underlyingLtp,
      BigDecimal underlyingDayOpen,
      OffsetDateTime asOf,
      BigDecimal combinedVwap,
      BigDecimal slBufferPoints,
      BigDecimal slLevel,
      List<StraddleCandle> items) {}

  private final InstrumentRepository instruments;
  private final OptionsChainService chainService;
  private final CandleQueryService candleQuery;
  private final QuoteGateway quoteGateway;
  private final Clock clock;

  public StraddleChartService(
      InstrumentRepository instruments,
      OptionsChainService chainService,
      CandleQueryService candleQuery,
      QuoteGateway quoteGateway,
      Clock clock) {
    this.instruments = instruments;
    this.chainService = chainService;
    this.candleQuery = candleQuery;
    this.quoteGateway = quoteGateway;
    this.clock = clock;
  }

  /**
   * Builds the combined-premium candle series. {@code requestedExpiry} null → nearest on/after today;
   * {@code callStrike}/{@code putStrike} both default to {@code strike} (straddle), override for a
   * strangle. {@code session} null → today IST. 422 when either leg has no listed instrument.
   */
  public StraddleChart chart(
      String underlying,
      LocalDate requestedExpiry,
      BigDecimal strike,
      BigDecimal callStrike,
      BigDecimal putStrike,
      int intervalMinutes,
      LocalDate session) {
    if (!ALLOWED_MINUTES.contains(intervalMinutes)) {
      throw new ApiException(
          400,
          ErrorCodes.VALIDATION_INTERVAL_UNSUPPORTED,
          "interval (minutes) must be one of " + ALLOWED_MINUTES.stream().sorted().toList());
    }
    LocalDate expiry = chainService.resolveExpiry(underlying, requestedExpiry);
    BigDecimal ceStrike = callStrike != null ? callStrike : strike;
    BigDecimal peStrike = putStrike != null ? putStrike : strike;

    List<Instrument> chain = instruments.optionChain(underlying, expiry);
    Instrument ce = leg(chain, ceStrike, "CE", underlying, expiry);
    Instrument pe = leg(chain, peStrike, "PE", underlying, expiry);

    LocalDate sess = session != null ? session : LocalDate.now(Ist.ZONE);
    OffsetDateTime from = sess.atTime(SESSION_OPEN).atOffset(Ist.OFFSET);
    OffsetDateTime to = sess.plusDays(1).atStartOfDay().atOffset(Ist.OFFSET);

    Map<OffsetDateTime, Ohlcv> ceBuckets = aggregate(ce, intervalMinutes, from, to);
    Map<OffsetDateTime, Ohlcv> peBuckets = aggregate(pe, intervalMinutes, from, to);

    List<StraddleCandle> items = new ArrayList<>();
    for (Map.Entry<OffsetDateTime, Ohlcv> e : ceBuckets.entrySet()) {
      Ohlcv c = e.getValue();
      Ohlcv p = peBuckets.get(e.getKey());
      if (p == null) {
        continue; // a straddle candle needs both legs in the bucket
      }
      items.add(
          new StraddleCandle(
              e.getKey(),
              c.open.add(p.open),
              c.high.add(p.high),
              c.low.add(p.low),
              c.close.add(p.close),
              c.close,
              p.close,
              c.volume + p.volume));
    }
    items.sort(Comparator.comparing(StraddleCandle::time));

    QuoteGateway.Quote underlyingQuote = underlyingQuote(chain, underlying);
    OffsetDateTime asOf = items.isEmpty() ? OffsetDateTime.now(clock) : items.get(items.size() - 1).time();
    BigDecimal combinedVwap = combinedVwap(items);
    BigDecimal slLevel = combinedVwap == null ? null : combinedVwap.subtract(SL_BUFFER_POINTS);
    return new StraddleChart(
        underlying,
        expiry,
        ceStrike,
        peStrike,
        intervalMinutes + "m",
        underlyingQuote == null ? null : underlyingQuote.lastPrice(),
        underlyingQuote == null || underlyingQuote.ohlc() == null ? null : underlyingQuote.ohlc().open(),
        asOf,
        combinedVwap,
        SL_BUFFER_POINTS,
        slLevel,
        items);
  }

  /**
   * The session combined-premium VWAP — typical-price ((H+L+C)/3) volume-weighted over the combined
   * candles; a zero-total-volume session falls back to the simple mean of the combined closes. null on
   * an empty session. Anchored at the session open (the items already start at 09:15 IST).
   */
  static BigDecimal combinedVwap(List<StraddleCandle> items) {
    if (items.isEmpty()) {
      return null;
    }
    BigDecimal pv = BigDecimal.ZERO;
    long vol = 0;
    for (StraddleCandle c : items) {
      BigDecimal typical = c.high().add(c.low()).add(c.close()).divide(THREE, 6, RoundingMode.HALF_UP);
      pv = pv.add(typical.multiply(BigDecimal.valueOf(c.volume())));
      vol += c.volume();
    }
    if (vol > 0) {
      return pv.divide(BigDecimal.valueOf(vol), 4, RoundingMode.HALF_UP);
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (StraddleCandle c : items) {
      sum = sum.add(c.close());
    }
    return sum.divide(BigDecimal.valueOf(items.size()), 4, RoundingMode.HALF_UP);
  }

  /** Reads the leg's 1m candles cache-first and folds them into session-aligned interval buckets. */
  private Map<OffsetDateTime, Ohlcv> aggregate(
      Instrument leg, int intervalMinutes, OffsetDateTime from, OffsetDateTime to) {
    List<Candle> oneMin =
        new ArrayList<>(candleQuery.read(leg.exchange(), leg.tradingsymbol(), "1m", from, to).items());
    oneMin.sort(Comparator.comparing(Candle::bucket)); // close = last bar in the bucket by time
    Map<OffsetDateTime, Ohlcv> buckets = new TreeMap<>();
    for (Candle bar : oneMin) {
      OffsetDateTime bucket = bucketStart(bar.bucket(), intervalMinutes);
      buckets.computeIfAbsent(bucket, k -> new Ohlcv()).fold(bar);
    }
    return buckets;
  }

  /** Floors a 1m bucket to the session-aligned interval boundary (open + k·N from 09:15 IST). */
  private static OffsetDateTime bucketStart(OffsetDateTime barTime, int intervalMinutes) {
    OffsetDateTime open =
        barTime.atZoneSameInstant(Ist.ZONE).toLocalDate().atTime(SESSION_OPEN).atOffset(Ist.OFFSET);
    long minutesSinceOpen = Duration.between(open, barTime).toMinutes();
    if (minutesSinceOpen < 0) {
      return open; // pre-open prints (rare) bucket to the open
    }
    long bucketIndex = minutesSinceOpen / intervalMinutes;
    return open.plusMinutes(bucketIndex * intervalMinutes);
  }

  private Instrument leg(
      List<Instrument> chain,
      BigDecimal strike,
      String type,
      String underlying,
      LocalDate expiry) {
    return chain.stream()
        .filter(i -> type.equals(i.instrumentType()) && i.strike().compareTo(strike) == 0)
        .findFirst()
        .orElseThrow(
            () ->
                new ApiException(
                    422,
                    ErrorCodes.DATA_GAP,
                    "no " + type + " instrument for " + underlying + " " + expiry + " @ " + strike));
  }

  /**
   * The underlying spot quote (for the header LTP + day-open) — BEST EFFORT. The chart's candle data
   * is read cache-first and needs no live quote, so a quote-gateway failure (e.g. no live Kite session
   * off-hours → {@code KITE_TOKEN_EXPIRED}) must NOT block the chart: swallow it and null the header.
   */
  private QuoteGateway.Quote underlyingQuote(List<Instrument> chain, String underlying) {
    String exch =
        chain.isEmpty() || chain.get(0).underlyingExchange() == null
            ? "NSE"
            : chain.get(0).underlyingExchange();
    InstrumentKey key = new InstrumentKey(exch, underlying);
    try {
      return Optional.ofNullable(quoteGateway.quotes(List.of(key)).get(key)).orElse(null);
    } catch (RuntimeException quoteUnavailable) {
      return null; // header degrades to "—"; the candle series still renders
    }
  }

  /** Mutable OHLCV accumulator for one interval bucket (1m bars folded in time order). */
  private static final class Ohlcv {
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private long volume;

    void fold(Candle bar) {
      if (open == null) {
        open = bar.open();
        high = bar.high();
        low = bar.low();
      } else {
        high = high.max(bar.high());
        low = low.min(bar.low());
      }
      close = bar.close();
      volume += bar.volume();
    }
  }
}
