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
import in.arthayantra.marketdata.options.OiInterval;
import in.arthayantra.marketdata.options.OptionsChainService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * oipulse "Options Chart" (plan §options/options-chart) — per LEG (Call + Put of one strike) a candlestick
 * of the OPTION PREMIUM (right axis) + the OI LINE (left axis), the options sibling of the Futures OI
 * Chart. Same two-table merge: the premium candles are the leg's intraday 1m bars read cache-first via
 * {@link CandleQueryService} (REAL OHLC — the identical mechanism {@link StraddleChartService} uses,
 * minus the leg-sum) and folded to the requested interval; the OI + IV line ride
 * {@link OptionsSnapshotReader#strikeSeries} (no new SQL) re-keyed onto the SAME 09:15-IST candle grid
 * (last-wins, left-join, null where no sample). One fetch returns BOTH legs; the FE Show toggle picks
 * which render. OI/IV are read at the finest 3m grain and re-keyed, so on the 1-min interval they floor
 * to ~3m (a disclosed, honest divergence — same as the futures chart; not a fabrication). The candle
 * close (broker 1m) is NOT the snapshot ltp — two sources at two cadences; the candle drives the body,
 * the snapshot drives only oi/iv.
 */
@Service
public class OptionsOiChartService {

  /** The cash-session open — candle + OI/IV buckets both align here. */
  private static final LocalTime SESSION_OPEN = LocalTime.of(9, 15);

  /** The oipulse interval set in minutes (INCLUDING 1-min, the page's defining feature). */
  private static final Set<Integer> ALLOWED_MINUTES = Set.of(1, 3, 5, 10, 15, 30, 60);

  /** The fine grain the OI/IV snapshot series is read at, then re-keyed onto the candle grid (≈ #57). */
  private static final OiInterval OI_GRAIN = OiInterval.M3;

  /**
   * One interval's leg candle: real per-bucket premium OHLC + the leg's OI + IV at that bucket.
   *
   * <p>OHLC + volume always exist (a bucket is only created by folding at least one 1m bar into it),
   * but {@code oi}/{@code iv} ride a LEFT JOIN from the snapshot series — null on every candle whose
   * bucket had no snapshot, which is the documented behaviour of this chart, not an edge case.
   *
   * <p>The premium OHLC and {@code iv} are {@code string} on the wire, not {@code number} —
   * {@code ArthaJacksonAutoConfiguration} serializes every {@code BigDecimal} via {@code
   * ToStringSerializer}. {@code volume} is a primitive {@code long} and is a real JSON number.
   */
  public record OptCandle(
      OffsetDateTime time,
      @Schema(type = "string") BigDecimal open,
      @Schema(type = "string") BigDecimal high,
      @Schema(type = "string") BigDecimal low,
      @Schema(type = "string") BigDecimal close,
      long volume,
      @Schema(types = {"integer", "null"}) Long oi,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal iv) {}

  /**
   * The options-chart payload: the header strip + the CE and PE per-interval candle+OI/IV series.
   *
   * <p>⚠️ COMPONENT ORDER IS THE WIRE ORDER and is deliberate: {@code ce}/{@code pe} lead. Until D3
   * the controller re-emitted this record through a {@code LinkedHashMap} that listed ce/pe first,
   * so that WAS the emitted order; the components were reordered to match when the handler started
   * returning this record directly, keeping the response byte-identical instead of quietly
   * reshuffling a live OI page's payload. Do not "tidy" this back into header-first order.
   *
   * <p>{@code underlyingLtp}/{@code underlyingDayOpen} are null whenever the underlying quote does
   * not resolve (off-hours, or a quote-gateway failure the chart deliberately survives), and like
   * every {@code BigDecimal} on this platform they are {@code string} on the wire, not {@code
   * number} — see {@code ArthaJacksonAutoConfiguration}.
   */
  public record OptOiChart(
      List<OptCandle> ce,
      List<OptCandle> pe,
      String underlying,
      LocalDate expiry,
      @Schema(type = "string") BigDecimal strike,
      String ceTradingsymbol,
      String peTradingsymbol,
      String interval,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal underlyingLtp,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal underlyingDayOpen,
      OffsetDateTime asOf) {}

  private final InstrumentRepository instruments;
  private final OptionsChainService chainService;
  private final CandleQueryService candleQuery;
  private final OptionsSnapshotReader reader;
  private final QuoteGateway quoteGateway;
  private final Clock clock;

  public OptionsOiChartService(
      InstrumentRepository instruments,
      OptionsChainService chainService,
      CandleQueryService candleQuery,
      OptionsSnapshotReader reader,
      QuoteGateway quoteGateway,
      Clock clock) {
    this.instruments = instruments;
    this.chainService = chainService;
    this.candleQuery = candleQuery;
    this.reader = reader;
    this.quoteGateway = quoteGateway;
    this.clock = clock;
  }

  /**
   * Builds the CE + PE premium candle + OI/IV series for one strike. {@code requestedExpiry} null →
   * nearest on/after today; {@code session} null → today IST. 422 when the strike has no listed leg.
   */
  public OptOiChart chart(
      String underlying,
      LocalDate requestedExpiry,
      BigDecimal strike,
      int intervalMinutes,
      LocalDate session) {
    if (!ALLOWED_MINUTES.contains(intervalMinutes)) {
      throw new ApiException(
          400,
          ErrorCodes.VALIDATION_INTERVAL_UNSUPPORTED,
          "interval (minutes) must be one of " + ALLOWED_MINUTES.stream().sorted().toList());
    }
    LocalDate expiry = chainService.resolveExpiry(underlying, requestedExpiry);
    List<Instrument> chain = instruments.optionChain(underlying, expiry);
    Instrument ce = leg(chain, strike, "CE", underlying, expiry);
    Instrument pe = leg(chain, strike, "PE", underlying, expiry);

    LocalDate sess = session != null ? session : LocalDate.now(Ist.ZONE);
    OffsetDateTime from = sess.atTime(SESSION_OPEN).atOffset(Ist.OFFSET);
    OffsetDateTime to = sess.plusDays(1).atStartOfDay().atOffset(Ist.OFFSET);

    // One snapshot read for the strike returns BOTH legs; split by type and re-key onto the candle grid.
    Map<String, Map<OffsetDateTime, OptionsSnapshotReader.StrikePoint>> oiByType = new LinkedHashMap<>();
    oiByType.put("CE", new LinkedHashMap<>());
    oiByType.put("PE", new LinkedHashMap<>());
    for (OptionsSnapshotReader.StrikePoint p :
        reader.strikeSeries(underlying, expiry, strike, OI_GRAIN, from, to)) {
      Map<OffsetDateTime, OptionsSnapshotReader.StrikePoint> m = oiByType.get(p.optionType());
      if (m != null) {
        m.put(bucketStart(p.bucket(), intervalMinutes), p); // last-wins
      }
    }

    List<OptCandle> ceItems = legCandles(ce, intervalMinutes, from, to, oiByType.get("CE"));
    List<OptCandle> peItems = legCandles(pe, intervalMinutes, from, to, oiByType.get("PE"));

    QuoteGateway.Quote uq = underlyingQuote(chain, underlying);
    OffsetDateTime asOf = lastTime(ceItems, peItems);
    return new OptOiChart(
        ceItems,
        peItems,
        underlying,
        expiry,
        strike,
        ce.tradingsymbol(),
        pe.tradingsymbol(),
        intervalMinutes + "m",
        uq == null ? null : uq.lastPrice(),
        uq == null || uq.ohlc() == null ? null : uq.ohlc().open(),
        asOf);
  }

  /** Folds one leg's 1m candles to the interval and left-joins its re-keyed OI/IV (null when absent). */
  private List<OptCandle> legCandles(
      Instrument leg,
      int intervalMinutes,
      OffsetDateTime from,
      OffsetDateTime to,
      Map<OffsetDateTime, OptionsSnapshotReader.StrikePoint> oiMap) {
    Map<OffsetDateTime, Ohlcv> candles = aggregate(leg, intervalMinutes, from, to);
    List<OptCandle> out = new ArrayList<>(candles.size());
    for (Map.Entry<OffsetDateTime, Ohlcv> e : candles.entrySet()) {
      Ohlcv c = e.getValue();
      OptionsSnapshotReader.StrikePoint sp = oiMap == null ? null : oiMap.get(e.getKey());
      out.add(
          new OptCandle(
              e.getKey(),
              c.open,
              c.high,
              c.low,
              c.close,
              c.volume,
              sp == null ? null : sp.oi(),
              sp == null ? null : sp.iv()));
    }
    out.sort(Comparator.comparing(OptCandle::time));
    return out;
  }

  private OffsetDateTime lastTime(List<OptCandle> ce, List<OptCandle> pe) {
    OffsetDateTime last = null;
    if (!ce.isEmpty()) {
      last = ce.get(ce.size() - 1).time();
    }
    if (!pe.isEmpty()) {
      OffsetDateTime p = pe.get(pe.size() - 1).time();
      last = last == null || p.isAfter(last) ? p : last;
    }
    return last != null ? last : OffsetDateTime.now(clock);
  }

  // ── lifted from StraddleChartService (the proven per-leg candle path), legs NOT summed ──────────────

  /** Reads the leg's 1m candles cache-first and folds them into session-aligned interval buckets. */
  private Map<OffsetDateTime, Ohlcv> aggregate(
      Instrument leg, int intervalMinutes, OffsetDateTime from, OffsetDateTime to) {
    List<Candle> oneMin =
        new ArrayList<>(
            candleQuery.read(leg.exchange(), leg.tradingsymbol(), "1m", from, to).items());
    oneMin.sort(Comparator.comparing(Candle::bucket)); // close = last bar in the bucket by time
    Map<OffsetDateTime, Ohlcv> buckets = new TreeMap<>();
    for (Candle bar : oneMin) {
      buckets.computeIfAbsent(bucketStart(bar.bucket(), intervalMinutes), k -> new Ohlcv()).fold(bar);
    }
    return buckets;
  }

  /** Floors a timestamp to the session-aligned interval boundary (open + k·N from 09:15 IST). */
  private static OffsetDateTime bucketStart(OffsetDateTime ts, int intervalMinutes) {
    OffsetDateTime open =
        ts.atZoneSameInstant(Ist.ZONE).toLocalDate().atTime(SESSION_OPEN).atOffset(Ist.OFFSET);
    long minutesSinceOpen = Duration.between(open, ts).toMinutes();
    if (minutesSinceOpen < 0) {
      return open; // pre-open prints (rare) bucket to the open
    }
    return open.plusMinutes((minutesSinceOpen / intervalMinutes) * intervalMinutes);
  }

  private Instrument leg(
      List<Instrument> chain, BigDecimal strike, String type, String underlying, LocalDate expiry) {
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

  /** Best-effort underlying spot quote for the header — a quote-gateway failure must NOT block the chart. */
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
