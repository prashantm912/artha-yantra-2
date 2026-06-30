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
 * oipulse "Calendar Spread Chart" (study {@code strategies/calendar-spread}) — the premium
 * DIFFERENTIAL of one (strike, optionType) across two expiries, charted as a candle series so the
 * time-decay / horizontal-spread edge is visible. Faithful to the source: per interval the spread
 * candle is the NEAR leg minus the FAR leg ({@code O = nearO − farO}, {@code C = nearC − farC}); the
 * high/low of a difference is the envelope ({@code H = nearH − farL}, {@code L = nearL − farH}); each
 * leg's close + the summed volume ride alongside. The FE overlays VWAP / 20-EMA / day H-L pins (pure
 * client maths).
 *
 * <p>Same no-new-capture path as {@link StraddleChartService}: each leg's intraday OHLC is read
 * cache-first at {@code 1m} via {@link CandleQueryService} and aggregated to the requested interval,
 * session-aligned to the 09:15 IST open. Differs from the straddle only in WHICH two legs are combined
 * (same strike/type across two expiries, differenced) and that there is no SL construct.
 */
@Service
public class CalendarSpreadChartService {

  /** The cash-session open — spread buckets align here (faithful to the straddle chart). */
  private static final LocalTime SESSION_OPEN = LocalTime.of(9, 15);

  /** The oipulse interval set in minutes (1/3/5/10/15/30/60), wider than {@code OiInterval}. */
  private static final Set<Integer> ALLOWED_MINUTES = Set.of(1, 3, 5, 10, 15, 30, 60);

  /** One interval's spread candle: (near − far) OHLC + each leg's close + summed volume. */
  public record SpreadCandle(
      OffsetDateTime time,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal close,
      BigDecimal nearClose,
      BigDecimal farClose,
      long volume) {}

  /**
   * The calendar-spread chart payload: the header strip + the per-interval (near − far) spread candles.
   * {@code optionType} is CE/PE; {@code nearExpiry} is the closer leg, {@code farExpiry} the later one.
   */
  public record CalendarSpreadChart(
      String underlying,
      BigDecimal strike,
      String optionType,
      LocalDate nearExpiry,
      LocalDate farExpiry,
      String interval,
      BigDecimal underlyingLtp,
      BigDecimal underlyingDayOpen,
      OffsetDateTime asOf,
      List<SpreadCandle> items) {}

  private final InstrumentRepository instruments;
  private final OptionsChainService chainService;
  private final CandleQueryService candleQuery;
  private final QuoteGateway quoteGateway;
  private final Clock clock;

  public CalendarSpreadChartService(
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
   * Builds the (near − far) spread candle series for one (strike, optionType). {@code nearExpiry} null
   * → nearest on/after today; {@code farExpiry} null → the next listed expiry after near. {@code
   * session} null → today IST. 400 on an off-set interval / when far ≤ near; 422 when either leg has no
   * listed instrument.
   */
  public CalendarSpreadChart chart(
      String underlying,
      BigDecimal strike,
      String optionType,
      LocalDate nearExpiry,
      LocalDate farExpiry,
      int intervalMinutes,
      LocalDate session) {
    if (!ALLOWED_MINUTES.contains(intervalMinutes)) {
      throw new ApiException(
          400,
          ErrorCodes.VALIDATION_INTERVAL_UNSUPPORTED,
          "interval (minutes) must be one of " + ALLOWED_MINUTES.stream().sorted().toList());
    }
    String type = optionType == null ? "CE" : optionType.trim().toUpperCase();
    if (!"CE".equals(type) && !"PE".equals(type)) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "optionType must be CE or PE");
    }
    LocalDate near = chainService.resolveExpiry(underlying, nearExpiry);
    LocalDate far = farExpiry != null ? farExpiry : nextExpiryAfter(underlying, near);
    if (far == null || !far.isAfter(near)) {
      throw new ApiException(
          400, ErrorCodes.VALIDATION_FAILED, "farExpiry must be after nearExpiry (" + near + ")");
    }

    Instrument nearLeg = leg(instruments.optionChain(underlying, near), strike, type, underlying, near);
    Instrument farLeg = leg(instruments.optionChain(underlying, far), strike, type, underlying, far);

    LocalDate sess = session != null ? session : LocalDate.now(Ist.ZONE);
    OffsetDateTime from = sess.atTime(SESSION_OPEN).atOffset(Ist.OFFSET);
    OffsetDateTime to = sess.plusDays(1).atStartOfDay().atOffset(Ist.OFFSET);

    Map<OffsetDateTime, Ohlcv> nearBuckets = aggregate(nearLeg, intervalMinutes, from, to);
    Map<OffsetDateTime, Ohlcv> farBuckets = aggregate(farLeg, intervalMinutes, from, to);

    List<SpreadCandle> items = new ArrayList<>();
    for (Map.Entry<OffsetDateTime, Ohlcv> e : nearBuckets.entrySet()) {
      Ohlcv n = e.getValue();
      Ohlcv f = farBuckets.get(e.getKey());
      if (f == null) {
        continue; // a spread candle needs both legs in the bucket
      }
      items.add(
          new SpreadCandle(
              e.getKey(),
              n.open.subtract(f.open),
              n.high.subtract(f.low), // the max of the difference: near high − far low
              n.low.subtract(f.high), // the min of the difference: near low − far high
              n.close.subtract(f.close),
              n.close,
              f.close,
              n.volume + f.volume));
    }
    items.sort(Comparator.comparing(SpreadCandle::time));

    List<Instrument> chain = instruments.optionChain(underlying, near);
    QuoteGateway.Quote q = underlyingQuote(chain, underlying);
    OffsetDateTime asOf =
        items.isEmpty() ? OffsetDateTime.now(clock) : items.get(items.size() - 1).time();
    return new CalendarSpreadChart(
        underlying,
        strike,
        type,
        near,
        far,
        intervalMinutes + "m",
        q == null ? null : q.lastPrice(),
        q == null || q.ohlc() == null ? null : q.ohlc().open(),
        asOf,
        items);
  }

  /** The first listed expiry strictly after {@code near} for the underlying, or null when none. */
  private LocalDate nextExpiryAfter(String underlying, LocalDate near) {
    return instruments.expiries(underlying).stream()
        .filter(d -> d.isAfter(near))
        .min(Comparator.naturalOrder())
        .orElse(null);
  }

  /** Reads the leg's 1m candles cache-first and folds them into session-aligned interval buckets. */
  private Map<OffsetDateTime, Ohlcv> aggregate(
      Instrument leg, int intervalMinutes, OffsetDateTime from, OffsetDateTime to) {
    List<Candle> oneMin =
        new ArrayList<>(candleQuery.read(leg.exchange(), leg.tradingsymbol(), "1m", from, to).items());
    oneMin.sort(Comparator.comparing(Candle::bucket));
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
      return open;
    }
    long bucketIndex = minutesSinceOpen / intervalMinutes;
    return open.plusMinutes(bucketIndex * intervalMinutes);
  }

  private Instrument leg(
      List<Instrument> chain, BigDecimal strike, String type, String underlying, LocalDate expiry) {
    if (strike == null) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "strike is required");
    }
    return chain.stream()
        .filter(i -> type.equals(i.instrumentType()) && i.strike() != null && i.strike().compareTo(strike) == 0)
        .findFirst()
        .orElseThrow(
            () ->
                new ApiException(
                    422,
                    ErrorCodes.DATA_GAP,
                    "no " + type + " instrument for " + underlying + " " + expiry + " @ " + strike));
  }

  /** The underlying spot quote (header LTP + day-open) — BEST EFFORT (null on an off-hours failure). */
  private QuoteGateway.Quote underlyingQuote(List<Instrument> chain, String underlying) {
    String exch =
        chain.isEmpty() || chain.get(0).underlyingExchange() == null
            ? "NSE"
            : chain.get(0).underlyingExchange();
    InstrumentKey key = new InstrumentKey(exch, underlying);
    try {
      return Optional.ofNullable(quoteGateway.quotes(List.of(key)).get(key)).orElse(null);
    } catch (RuntimeException quoteUnavailable) {
      return null;
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
