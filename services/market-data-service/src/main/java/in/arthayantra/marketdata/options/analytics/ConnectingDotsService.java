package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleQueryService;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.GlobalQuoteSource;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.options.OiInterval;
import in.arthayantra.marketdata.options.OptionsChainService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * oipulse "Connecting Dots" (§20.7.8) — the per-interval multi-factor sentiment matrix. For an index,
 * each interval bucket is a row of 11 factors each rated 3-state (0 Neutral / 1 Bullish / 2 Bearish)
 * plus a 5-state composite Trend.
 *
 * <p>Factor sources (all via exposed module APIs — no cross-module-internal reach): the front-month
 * FUTURES candle series (cache-first {@link CandleQueryService}) drives Price / VWAP / Supertrend /
 * RSI / Volume / OI-Interpretation (the candle's {@code oi}); the INDEX daily candle drives Daily
 * Trend; INDIA VIX candles drive Vix; the option snapshots ({@link OptionsSnapshotReader} +
 * {@link ActiveStrikeService}) drive Active-Strike OI and Active-Strike IV. Dow Jones rides the
 * dedicated global-quote feed ({@link GlobalQuoteSource}, OpenAlgo- or Upstox-backed) — live
 * LTP-direction, Neutral in history mode or when the feed is unconfigured (the study confirms
 * {@code inDow≈0} during Indian hours anyway).
 *
 * <p>The exact oipulse per-factor raw→enum cutoffs and the composite WEIGHTS are server-side (unknown);
 * ours are a documented approximation (the composite uses the empirically-fitted net→trend mapping from
 * the study doc), the same intended divergence class as black76 greeks vs oipulse's server greeks.
 */
@Service
public class ConnectingDotsService {

  private static final Logger log = LoggerFactory.getLogger(ConnectingDotsService.class);

  private static final LocalTime SESSION_OPEN = LocalTime.of(9, 15);
  private static final int RSI_PERIOD = 14;
  private static final int ST_PERIOD = 10;
  private static final double ST_MULT = 3.0;

  /** OpenAlgo {@code DOWJONES@GLOBAL_INDEX} — the Dow factor's global-index key (plan §3). */
  private static final InstrumentKey DOW = new InstrumentKey("GLOBAL_INDEX", "DOWJONES");

  static final int NEUTRAL = 0;
  static final int BULLISH = 1;
  static final int BEARISH = 2;

  /** One interval row: the 11 factor codes (0/1/2) + the 5-state composite trend (0..4). */
  public record ConnectingDotsRow(
      String timeInterval,
      int trend,
      int dow,
      int vix,
      int volume,
      int activeStrikeIv,
      int activeStrikeOi,
      int futOi,
      int vwap,
      int supertrend,
      int rsi,
      int futPrice,
      int dailyTrend) {}

  /**
   * {@code derived} = the OI factors were reconstructed from per-contract candles (no captured
   * snapshots for the session) — a provenance badge for the OI pages; the ATM-IV factor is then
   * NEUTRAL (candles carry no IV). False for live/captured sessions.
   */
  public record ConnectingDots(
      String underlying,
      String interval,
      OffsetDateTime asOf,
      boolean derived,
      List<ConnectingDotsRow> rows) {}

  private final InstrumentRepository instruments;
  private final CandleQueryService candleQuery;
  private final OptionsChainService chainService;
  private final OptionsSnapshotReader snapshotReader;
  private final ActiveStrikeService activeStrikeService;
  private final CandleDerivedChainReader derivedChain;
  private final ObjectProvider<GlobalQuoteSource> dowSource;
  private final Clock clock;

  public ConnectingDotsService(
      InstrumentRepository instruments,
      CandleQueryService candleQuery,
      OptionsChainService chainService,
      OptionsSnapshotReader snapshotReader,
      ActiveStrikeService activeStrikeService,
      CandleDerivedChainReader derivedChain,
      ObjectProvider<GlobalQuoteSource> dowSource,
      Clock clock) {
    this.instruments = instruments;
    this.candleQuery = candleQuery;
    this.chainService = chainService;
    this.snapshotReader = snapshotReader;
    this.activeStrikeService = activeStrikeService;
    this.derivedChain = derivedChain;
    this.dowSource = dowSource;
    this.clock = clock;
  }

  /** Builds the matrix for {@code underlying} (an index) over the session, newest interval first. */
  public ConnectingDots matrix(String underlying, OiInterval interval, LocalDate session) {
    LocalDate sess = session != null ? session : LocalDate.now(Ist.ZONE);
    OffsetDateTime from = sess.atStartOfDay().atOffset(Ist.OFFSET);
    OffsetDateTime now = OffsetDateTime.now(clock);
    OffsetDateTime to = sess.plusDays(1).atStartOfDay().atOffset(Ist.OFFSET);

    // ── the front-month futures candle spine (Price / VWAP / Supertrend / RSI / Volume / OI) ──
    Instrument fut = frontFuture(underlying, sess);
    List<Bar> bars =
        fut == null
            ? List.of()
            : resample(candleQuery.read(fut.exchange(), fut.tradingsymbol(), "1m", from, to).items(),
                interval);
    // history fallback: the live instruments table has no EXPIRED futures, so a past session's spine
    // reads empty — resolve that session's front future from the backfilled expired_contracts and
    // read its stored candles. Gated to fully-past windows so live is untouched.
    if (bars.isEmpty() && fullyHistorical(to)) {
      String eu = CandleDerivedChainReader.expiredUnderlying(underlying);
      CandleDerivedChainReader.FrontFuture ff = eu == null ? null : derivedChain.frontFuture(eu, sess);
      if (ff != null) {
        bars =
            resample(
                candleQuery.read(ff.exchange(), ff.tradingsymbol(), "1m", from, to).items(), interval);
      }
    }
    if (bars.isEmpty()) {
      return new ConnectingDots(underlying, interval.token(), now, false, List.of());
    }

    int n = bars.size();
    double[] highs = new double[n];
    double[] lows = new double[n];
    double[] closes = new double[n];
    double[] vols = new double[n];
    for (int i = 0; i < n; i++) {
      highs[i] = bars.get(i).high;
      lows[i] = bars.get(i).low;
      closes[i] = bars.get(i).close;
      vols[i] = bars.get(i).volume;
    }
    double[] rsi = ConnectingDotsIndicators.rsi(closes, RSI_PERIOD);
    double[] vwap = ConnectingDotsIndicators.vwap(highs, lows, closes, vols);
    int[] stDir = ConnectingDotsIndicators.supertrendDirection(highs, lows, closes, ST_PERIOD, ST_MULT);

    // ── the other-source factors, keyed by the SAME midnight-IST bucket as the futures spine ──
    int dailyTrend = dailyTrend(underlying, fut, sess);
    int dow = dowFactor(sess); // one global cue per session: live LTP-direction, Neutral in history
    // The other-source factor maps are keyed by the bucket INSTANT (not the OffsetDateTime): the
    // futures-spine bars carry the IST offset while the OI/VIX series come back from JDBC time_bucket
    // at +00 — same instant, different offset, so an OffsetDateTime key silently misses every lookup
    // (the OI/IV/VIX factors read NEUTRAL on EVERY history session until this fix).
    Map<java.time.Instant, Double> vixClose = vixByBucket(interval, from, to);
    // chain OI: captured snapshots when present, else (fully-past session only) reconstructed from
    // per-contract candles — so OI-using history stops reading NEUTRAL. Live never enters the derive.
    ChainSeries chain = chainSeries(underlying, interval, from, to, sess);
    Map<java.time.Instant, Integer> activeOi = activeStrikeOiFromSeries(chain.points());
    Map<java.time.Instant, Double> atmIv = atmIvFromSeries(chain.points());

    List<ConnectingDotsRow> rows = new ArrayList<>(n);
    Double prevVix = null;
    Double prevAtmIv = null;
    for (int i = 0; i < n; i++) {
      Bar b = bars.get(i);
      boolean first = i == 0;
      double prevClose = first ? b.close : bars.get(i - 1).close;
      double prevVol = first ? b.volume : bars.get(i - 1).volume;

      int futPrice = dirOf(b.close - prevClose);
      int vwapF = dirOf(b.close - vwap[i]);
      int stF = stDir[i] > 0 ? BULLISH : stDir[i] < 0 ? BEARISH : NEUTRAL;
      int rsiF = rsiFactor(rsi[i]);
      int volF = volumeFactor(b.volume, prevVol, b.close - prevClose);
      int futOiF = futOiFactor(b, first ? null : bars.get(i - 1));

      Double vix = vixClose.get(b.bucket.toInstant());
      int vixF = vixFactor(vix, prevVix);
      if (vix != null) {
        prevVix = vix;
      }
      Double iv = atmIv.get(b.bucket.toInstant());
      int ivF = ivFactor(iv, prevAtmIv);
      if (iv != null) {
        prevAtmIv = iv;
      }
      int activeOiF = activeOi.getOrDefault(b.bucket.toInstant(), NEUTRAL);

      int[] factors = {dow, vixF, volF, ivF, activeOiF, futOiF, vwapF, stF, rsiF, futPrice, dailyTrend};
      int trend = composite(factors);
      rows.add(
          new ConnectingDotsRow(
              label(b.bucket, interval), trend, dow, vixF, volF, ivF, activeOiF, futOiF, vwapF, stF,
              rsiF, futPrice, dailyTrend));
    }
    rows.sort(Comparator.comparing(ConnectingDotsRow::timeInterval).reversed()); // newest interval first
    return new ConnectingDots(
        underlying, interval.token(), bars.get(n - 1).bucket, chain.derived(), rows);
  }

  // ── factor classifiers ─────────────────────────────────────────────────────────────────────

  /** +Δ → Bullish, −Δ → Bearish, flat → Neutral. */
  private static int dirOf(double delta) {
    if (delta > 0) {
      return BULLISH;
    }
    return delta < 0 ? BEARISH : NEUTRAL;
  }

  /** RSI bands: > 55 Bullish, < 45 Bearish, mid Neutral (warmup NaN → Neutral). */
  private static int rsiFactor(double rsi) {
    if (Double.isNaN(rsi)) {
      return NEUTRAL;
    }
    if (rsi > 55) {
      return BULLISH;
    }
    return rsi < 45 ? BEARISH : NEUTRAL;
  }

  /** Volume confirms the move: a bucket whose volume rose vs the prior bucket biases with price. */
  private static int volumeFactor(double vol, double prevVol, double priceDelta) {
    if (vol <= prevVol || priceDelta == 0) {
      return NEUTRAL;
    }
    return priceDelta > 0 ? BULLISH : BEARISH;
  }

  /** Futures OI interpretation (the 4-state collapsed to bull/bear): needs both legs of the bar pair. */
  private static int futOiFactor(Bar cur, Bar prev) {
    if (prev == null || cur.oi == null || prev.oi == null) {
      return NEUTRAL;
    }
    double priceDelta = cur.close - prev.close;
    long oiDelta = cur.oi - prev.oi;
    if (priceDelta == 0 || oiDelta == 0) {
      return NEUTRAL;
    }
    // long buildup / short covering (price up) = bullish; short buildup / long unwinding (down) = bearish
    return priceDelta > 0 ? BULLISH : BEARISH;
  }

  /** VIX is inverse: a falling VIX is Bullish for the index, a rising VIX Bearish. */
  private static int vixFactor(Double vix, Double prevVix) {
    if (vix == null || prevVix == null) {
      return NEUTRAL;
    }
    if (vix < prevVix) {
      return BULLISH;
    }
    return vix > prevVix ? BEARISH : NEUTRAL;
  }

  /** ATM IV: falling IV (complacency) Bullish, rising IV (fear) Bearish. */
  private static int ivFactor(Double iv, Double prevIv) {
    if (iv == null || prevIv == null) {
      return NEUTRAL;
    }
    if (iv < prevIv) {
      return BULLISH;
    }
    return iv > prevIv ? BEARISH : NEUTRAL;
  }

  /**
   * Composite 5-state Trend from the net vote (#bull − #bear). Uses the empirically-fitted mapping
   * from the study doc (oipulse's exact weights are server-side): net ≥ 8 → 1 (Ext.Bullish),
   * 2..7 → 2 (Bullish), −4..1 → 3 (Bearish), ≤ −5 → 4 (Ext.Bearish).
   */
  static int composite(int[] factors) {
    int net = 0;
    for (int f : factors) {
      if (f == BULLISH) {
        net++;
      } else if (f == BEARISH) {
        net--;
      }
    }
    if (net >= 8) {
      return 1;
    }
    if (net >= 2) {
      return 2;
    }
    return net >= -4 ? 3 : 4;
  }

  // ── other-source factor series (bucket-keyed to the futures spine) ──────────────────────────

  /**
   * Dow Jones factor (plan §3): a single global cue applied to every row of the session. Live mode
   * (the session IS today IST) → the {@code DOWJONES@GLOBAL_INDEX} LTP vs its prev close (globals are
   * LTP-only, so direction is from the OHLC close slot). History mode (a past date) → Neutral, since
   * there is no global historical series. Best-effort: the global feed bean is absent unless one of
   * {@code artha.openalgo.global-quotes-enabled} / {@code artha.upstox.global-quotes-enabled} is on,
   * and any fetch failure → Neutral (counted as {@code ay_global_quote_degraded_total}, never silent).
   *
   * <p>Package-private, not private, purely as a test seam: it is the ONLY place the global feed
   * reaches a factor code, and the live path cannot be reached from the session-scoped {@code matrix}
   * without a whole candle spine. Arithmetic and callers are unchanged.
   */
  int dowFactor(LocalDate sess) {
    LocalDate today = OffsetDateTime.now(clock).withOffsetSameInstant(Ist.OFFSET).toLocalDate();
    if (!sess.equals(today)) {
      return NEUTRAL;
    }
    GlobalQuoteSource source = dowSource.getIfAvailable();
    if (source == null) {
      return NEUTRAL;
    }
    return source
        .latest(DOW)
        .map(
            q ->
                q.lastPrice() == null || q.ohlc() == null || q.ohlc().close() == null
                    ? NEUTRAL
                    : dirOf(q.lastPrice().subtract(q.ohlc().close()).doubleValue()))
        .orElse(NEUTRAL);
  }

  private int dailyTrend(String underlying, Instrument fut, LocalDate sess) {
    String exch = indexExchange(fut);
    OffsetDateTime from = sess.minusDays(10).atStartOfDay().atOffset(Ist.OFFSET);
    OffsetDateTime to = sess.plusDays(1).atStartOfDay().atOffset(Ist.OFFSET);
    List<Candle> daily;
    try {
      daily = candleQuery.read(exch, underlying, "1d", from, to).items();
    } catch (RuntimeException e) {
      return NEUTRAL;
    }
    if (daily.size() < 2) {
      return NEUTRAL;
    }
    daily.sort(Comparator.comparing(Candle::bucket));
    Candle last = daily.get(daily.size() - 1);
    return dirOf(last.close().subtract(last.open()).doubleValue());
  }

  private Map<java.time.Instant, Double> vixByBucket(
      OiInterval interval, OffsetDateTime from, OffsetDateTime to) {
    List<Candle> vix;
    try {
      vix = candleQuery.read("NSE", "INDIA VIX", "1m", from, to).items();
    } catch (RuntimeException e) {
      log.debug("no INDIA VIX candles for connecting-dots: {}", e.getMessage());
      return Map.of();
    }
    Map<java.time.Instant, Double> out = new HashMap<>();
    for (Bar b : resample(vix, interval)) {
      out.put(b.bucket.toInstant(), b.close);
    }
    return out;
  }

  /** Captured snapshot series, or candle-derived for a fully-past session with none. */
  private record ChainSeries(List<OptionsSnapshotReader.StrikePoint> points, boolean derived) {}

  /**
   * The chain series for the session: captured {@code options_chain_snapshots} when present; else —
   * ONLY when the whole window is in the past (so the LIVE 5-min refresh never enters this branch) —
   * reconstructed from per-contract candles via {@link CandleDerivedChainReader}. Empty when neither
   * has data.
   */
  private ChainSeries chainSeries(
      String underlying, OiInterval interval, OffsetDateTime from, OffsetDateTime to, LocalDate sess) {
    LocalDate expiry = null;
    try {
      expiry = chainService.resolveExpiry(underlying, null);
    } catch (RuntimeException ignore) {
      // no current chain (e.g. a pure-history index) → fall through to the candle derivation
    }
    if (expiry != null) {
      List<OptionsSnapshotReader.StrikePoint> captured =
          snapshotReader.series(underlying, expiry, interval, from, to);
      if (!captured.isEmpty()) {
        return new ChainSeries(captured, false);
      }
    }
    if (!fullyHistorical(to)) {
      return new ChainSeries(List.of(), false); // live/today window — never derive
    }
    String eu = CandleDerivedChainReader.expiredUnderlying(underlying);
    if (eu == null) {
      return new ChainSeries(List.of(), false);
    }
    LocalDate histExpiry = derivedChain.frontExpiry(eu, sess);
    if (histExpiry == null) {
      return new ChainSeries(List.of(), false);
    }
    List<OptionsSnapshotReader.StrikePoint> derivedPoints =
        derivedChain.series(eu, histExpiry, interval, from, to);
    return new ChainSeries(derivedPoints, !derivedPoints.isEmpty());
  }

  /** True when {@code to} is at/before the start of today (IST) — the whole window is historical. */
  private boolean fullyHistorical(OffsetDateTime to) {
    OffsetDateTime todayStart =
        LocalDate.ofInstant(clock.instant(), Ist.ZONE).atStartOfDay().atOffset(Ist.OFFSET);
    return !to.isAfter(todayStart);
  }

  /** Active-Strike Sentiment % per bucket → sign: PE flow (>0) Bullish, CE flow (<0) Bearish. */
  private Map<java.time.Instant, Integer> activeStrikeOiFromSeries(
      List<OptionsSnapshotReader.StrikePoint> series) {
    Map<java.time.Instant, Integer> out = new HashMap<>();
    for (ActiveStrikeService.SentimentPoint p : activeStrikeService.sentimentSeries(series)) {
      out.put(
          p.bucket().toInstant(),
          p.sentimentPct() == null ? NEUTRAL : dirOf(p.sentimentPct().doubleValue()));
    }
    return out;
  }

  /** ATM (nearest-spot strike) average IV per bucket, for the IV-direction factor. */
  private Map<java.time.Instant, Double> atmIvFromSeries(
      List<OptionsSnapshotReader.StrikePoint> series) {
    Map<java.time.Instant, Double> out = new LinkedHashMap<>();
    Map<OffsetDateTime, List<OptionsSnapshotReader.StrikePoint>> byBucket = new LinkedHashMap<>();
    for (OptionsSnapshotReader.StrikePoint p : series) {
      byBucket.computeIfAbsent(p.bucket(), k -> new ArrayList<>()).add(p);
    }
    byBucket.forEach(
        (bucket, pts) -> {
          BigDecimal spot = pts.stream().map(OptionsSnapshotReader.StrikePoint::spot)
              .filter(java.util.Objects::nonNull).findFirst().orElse(null);
          if (spot == null) {
            return;
          }
          BigDecimal atm = pts.stream().map(OptionsSnapshotReader.StrikePoint::strike).distinct()
              .min(Comparator.comparing(s -> s.subtract(spot).abs())).orElse(null);
          if (atm == null) {
            return;
          }
          double sum = 0;
          int count = 0;
          for (OptionsSnapshotReader.StrikePoint p : pts) {
            if (p.strike().compareTo(atm) == 0 && p.iv() != null) {
              sum += p.iv().doubleValue();
              count++;
            }
          }
          if (count > 0) {
            out.put(bucket.toInstant(), sum / count);
          }
        });
    return out;
  }

  // ── bar resampling (midnight-IST aligned, matching pg time_bucket) ──────────────────────────

  /** One resampled interval bar of the futures spine. */
  private record Bar(
      OffsetDateTime bucket, double high, double low, double close, double volume, Long oi) {}

  /** Folds 1m candles into interval bars keyed to the midnight-IST {@code time_bucket} boundary. */
  private static List<Bar> resample(List<Candle> oneMin, OiInterval interval) {
    int minutes = (int) interval.bucket().toMinutes();
    Map<OffsetDateTime, double[]> hlcv = new TreeMap<>(); // [high, low, close, volume, lastEpoch]
    Map<OffsetDateTime, Long> oiAt = new TreeMap<>();
    List<Candle> sorted = new ArrayList<>(oneMin);
    sorted.sort(Comparator.comparing(Candle::bucket));
    for (Candle c : sorted) {
      OffsetDateTime bucket = bucketStart(c.bucket(), minutes);
      double[] v = hlcv.get(bucket);
      if (v == null) {
        hlcv.put(bucket, new double[] {c.high().doubleValue(), c.low().doubleValue(),
            c.close().doubleValue(), c.volume(), c.bucket().toEpochSecond()});
      } else {
        v[0] = Math.max(v[0], c.high().doubleValue());
        v[1] = Math.min(v[1], c.low().doubleValue());
        v[2] = c.close().doubleValue(); // sorted → last is the bucket close
        v[3] += c.volume();
        v[4] = c.bucket().toEpochSecond();
      }
      if (c.oi() != null) {
        oiAt.put(bucket, c.oi()); // last OI in the bucket
      }
    }
    List<Bar> bars = new ArrayList<>(hlcv.size());
    hlcv.forEach((bucket, v) -> bars.add(new Bar(bucket, v[0], v[1], v[2], v[3], oiAt.get(bucket))));
    return bars;
  }

  /** Floors an IST timestamp to the N-minute boundary from IST midnight (pg time_bucket parity). */
  private static OffsetDateTime bucketStart(OffsetDateTime time, int minutes) {
    OffsetDateTime ist = time.withOffsetSameInstant(Ist.OFFSET);
    LocalDate day = ist.toLocalDate();
    int minsSinceMidnight = ist.getHour() * 60 + ist.getMinute();
    int floored = (minsSinceMidnight / minutes) * minutes;
    return day.atStartOfDay().plusMinutes(floored).atOffset(Ist.OFFSET);
  }

  /** "HH:MM-HH:MM" bucket label (start → start+interval) in IST. */
  private static String label(OffsetDateTime bucket, OiInterval interval) {
    OffsetDateTime start = bucket.withOffsetSameInstant(Ist.OFFSET);
    OffsetDateTime end = start.plus(interval.bucket());
    return hhmm(start) + "-" + hhmm(end);
  }

  private static String hhmm(OffsetDateTime t) {
    return String.format("%02d:%02d", t.getHour(), t.getMinute());
  }

  /** Front-month future of {@code underlying}: nearest expiry on/after the session (else the nearest). */
  private Instrument frontFuture(String underlying, LocalDate sess) {
    List<Instrument> futures = instruments.futures(underlying);
    if (futures.isEmpty()) {
      return null;
    }
    return futures.stream()
        .filter(f -> f.expiry() != null && !f.expiry().isBefore(sess))
        .findFirst()
        .orElse(futures.get(0));
  }

  private static String indexExchange(Instrument fut) {
    return fut == null || fut.underlyingExchange() == null ? "NSE" : fut.underlyingExchange();
  }
}
