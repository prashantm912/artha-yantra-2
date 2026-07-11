package in.arthayantra.marketdata.context;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.canary.IngestHealthBoard;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleQueryService;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.options.OptionsDigestService;
import in.arthayantra.marketdata.upstox.UpstoxGlobalInstrumentsClient;
import in.arthayantra.marketdata.upstox.WorldIndex;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Day-context digest (intelligence-layer design 2026-07-10 §6.1.5) — the dashboard one-call the Focus
 * panel and the day-context strip render. It BUNDLES the market-data-native context plane for I1: the
 * options-digest headline (§6.1.1) for the primary index, the INDIA VIX level/Δ/band, the index
 * price-action read (gap-open / day-range vs its 20-session average / direction, off the existing
 * daily candles), the overnight global cues (top-mover world indices), session phase + holiday
 * proximity ({@link MarketCalendar}), and the ingest-trust summary (the {@code ingest_runs} batch-
 * source oracle via {@link IngestHealthBoard}).
 *
 * <p><b>Scope (I1).</b> The futures / equity / FII headline blocks (§6.1.2–4) are I2 and are NOT
 * bundled here — day-context carries only the blocks whose folds exist today, never a fabricated one
 * (§6.5). Every live-quote-backed block (VIX, overnight cues) is fail-soft: it degrades to a null/empty
 * value with a {@code notes} entry off-hours or when Upstox analytics is off, never a 5xx.
 *
 * <p><b>Trust.</b> The {@code ingestTrust} block is the batch-source ledger read (the §7 trust oracle's
 * ingest half). The full cross-service {@code TrustService} composition (adding live-capture health,
 * dot-health, and the market calendar) is the insights module's job in a later increment.
 */
@Service
public class DayContextService {

  /** INDIA VIX quote (level + change vs prev close + a coarse regime band). Null when unavailable. */
  public record Vix(
      BigDecimal level, BigDecimal change, BigDecimal changePct, String band, OffsetDateTime asOf) {}

  /** One overnight global-index cue (top mover by |%change|). */
  public record GlobalCue(String name, BigDecimal ltp, BigDecimal changePct) {}

  /** Primary-index price action off the daily candles — the intraday/overnight structure read. */
  public record IndexPriceAction(
      String symbol,
      BigDecimal gapOpenPct,
      BigDecimal dayRange,
      BigDecimal avgRange20,
      BigDecimal rangeVsAvg,
      String direction,
      String rangeState,
      LocalDate asOfDate) {}

  /** Holiday proximity: whether today is a listed holiday and the next one (name + days away). */
  public record HolidayProximity(
      boolean holidayToday, LocalDate nextHoliday, String nextHolidayName, Integer daysToNextHoliday) {}

  /** One batch source's trust cell (mapped from the ingest health board). */
  public record SourceTrust(
      String source, String status, String lastRunStatus, OffsetDateTime lastRunAt, boolean stale) {}

  /** The ingest-trust summary: worst-of overall + per-source cells (OK / DEGRADED / BLOCKED). */
  public record IngestTrust(String overall, List<SourceTrust> sources) {}

  /** The bundled day context. */
  public record DayContext(
      LocalDate tradeDate,
      String sessionPhase,
      HolidayProximity holiday,
      OptionsDigestService.OptionsDigest options,
      Vix vix,
      List<GlobalCue> overnightCues,
      IndexPriceAction indexPriceAction,
      IngestTrust ingestTrust,
      OffsetDateTime asOf,
      List<String> notes) {}

  private static final Logger log = LoggerFactory.getLogger(DayContextService.class);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  // Coarse INDIA-VIX regime bands (heuristic defaults, config-overridable). Labels only — never a gate.
  private static final BigDecimal RANGE_WIDE = BigDecimal.valueOf(1.2);
  private static final BigDecimal RANGE_NARROW = BigDecimal.valueOf(0.8);

  private final OptionsDigestService optionsDigest;
  private final QuoteGateway quoteGateway;
  private final ObjectProvider<UpstoxGlobalInstrumentsClient> worldIndices;
  private final CandleQueryService candles;
  private final IngestHealthBoard healthBoard;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final InstrumentKey vixKey;
  private final String optionsName;
  private final String indexExchange;
  private final String indexSymbol;
  private final int overnightTopN;
  private final int trustLookback;
  private final BigDecimal vixNormal;
  private final BigDecimal vixElevated;
  private final BigDecimal vixHigh;

  /** Wires the reused digest + the market-data-native context ports and the display/config knobs. */
  public DayContextService(
      OptionsDigestService optionsDigest,
      QuoteGateway quoteGateway,
      ObjectProvider<UpstoxGlobalInstrumentsClient> worldIndices,
      CandleQueryService candles,
      IngestHealthBoard healthBoard,
      MarketCalendar calendar,
      Clock clock,
      @Value("${artha.context.options-name:NIFTY}") String optionsName,
      @Value("${artha.context.index-exchange:NSE}") String indexExchange,
      @Value("${artha.context.index-symbol:NIFTY 50}") String indexSymbol,
      @Value("${artha.context.vix-instrument:INDIA VIX}") String vixSymbol,
      @Value("${artha.context.overnight-top-n:5}") int overnightTopN,
      @Value("${artha.context.trust-lookback-days:5}") int trustLookback,
      @Value("${artha.context.vix-normal:13}") BigDecimal vixNormal,
      @Value("${artha.context.vix-elevated:17}") BigDecimal vixElevated,
      @Value("${artha.context.vix-high:22}") BigDecimal vixHigh) {
    this.optionsDigest = optionsDigest;
    this.quoteGateway = quoteGateway;
    this.worldIndices = worldIndices;
    this.candles = candles;
    this.healthBoard = healthBoard;
    this.calendar = calendar;
    this.clock = clock;
    this.vixKey = new InstrumentKey("NSE", vixSymbol);
    this.optionsName = optionsName;
    this.indexExchange = indexExchange;
    this.indexSymbol = indexSymbol;
    this.overnightTopN = overnightTopN;
    this.trustLookback = trustLookback;
    this.vixNormal = vixNormal;
    this.vixElevated = vixElevated;
    this.vixHigh = vixHigh;
  }

  /** Assemble the day-context one-call for the configured primary index. */
  public DayContext dayContext() {
    OffsetDateTime nowIst = OffsetDateTime.now(clock).withOffsetSameInstant(Ist.OFFSET);
    LocalDate today = nowIst.toLocalDate();
    List<String> notes = new ArrayList<>();

    boolean tradingDay;
    LocalDate tradeDate;
    try {
      tradingDay = calendar.isTradingDay(today);
      tradeDate = tradingDay ? today : calendar.previousTradingDay(today);
    } catch (IllegalArgumentException uncoveredYear) {
      tradingDay = false;
      tradeDate = today;
      notes.add("trading calendar does not cover " + today);
    }
    String phase = sessionPhase(tradingDay, nowIst.toLocalTime());
    HolidayProximity holiday = holidayProximity(today);

    OptionsDigestService.OptionsDigest options = null;
    try {
      options = optionsDigest.digest(optionsName, null, null);
    } catch (RuntimeException e) {
      notes.add("options digest unavailable for " + optionsName + ": " + e.getMessage());
    }

    Vix vix = vix(notes);
    List<GlobalCue> cues = overnightCues(notes);
    IndexPriceAction ipa = indexPriceAction(nowIst, notes);
    IngestTrust trust = ingestTrust();

    return new DayContext(
        tradeDate, phase, holiday, options, vix, cues, ipa, trust, nowIst, List.copyOf(notes));
  }

  private static String sessionPhase(boolean tradingDay, LocalTime now) {
    if (!tradingDay) {
      return "HOLIDAY";
    }
    if (now.isBefore(MarketCalendar.SESSION_OPEN)) {
      return "PRE_OPEN";
    }
    if (!now.isBefore(MarketCalendar.SESSION_CLOSE)) {
      return "POST_CLOSE";
    }
    return "OPEN";
  }

  private HolidayProximity holidayProximity(LocalDate today) {
    List<MarketCalendar.Holiday> all = calendar.holidayList();
    boolean holidayToday = all.stream().anyMatch(h -> h.date().equals(today));
    MarketCalendar.Holiday next =
        all.stream()
            .filter(h -> h.date().isAfter(today))
            .min(Comparator.comparing(MarketCalendar.Holiday::date))
            .orElse(null);
    return new HolidayProximity(
        holidayToday,
        next == null ? null : next.date(),
        next == null ? null : next.name(),
        next == null ? null : (int) ChronoUnit.DAYS.between(today, next.date()));
  }

  private Vix vix(List<String> notes) {
    try {
      QuoteGateway.Quote q = quoteGateway.quotes(List.of(vixKey)).get(vixKey);
      if (q == null || q.lastPrice() == null) {
        notes.add("INDIA VIX quote unavailable (off-hours / analytics off)");
        return null;
      }
      QuoteGateway.Quote.Ohlc o = q.ohlc();
      BigDecimal prevClose = o == null ? null : o.close();
      BigDecimal change = prevClose == null ? null : q.lastPrice().subtract(prevClose);
      BigDecimal changePct =
          prevClose == null || prevClose.signum() == 0
              ? null
              : change.multiply(HUNDRED).divide(prevClose, 4, RoundingMode.HALF_UP);
      return new Vix(q.lastPrice(), change, changePct, vixBand(q.lastPrice()), q.timestamp());
    } catch (RuntimeException e) {
      notes.add("INDIA VIX read failed: " + e.getMessage());
      return null;
    }
  }

  /** Coarse regime label from the VIX level (LOW / NORMAL / ELEVATED / HIGH) — a display band only. */
  private String vixBand(BigDecimal level) {
    if (level.compareTo(vixNormal) < 0) {
      return "LOW";
    }
    if (level.compareTo(vixElevated) < 0) {
      return "NORMAL";
    }
    if (level.compareTo(vixHigh) < 0) {
      return "ELEVATED";
    }
    return "HIGH";
  }

  private List<GlobalCue> overnightCues(List<String> notes) {
    UpstoxGlobalInstrumentsClient client = worldIndices.getIfAvailable();
    if (client == null) {
      notes.add("overnight global cues unavailable (upstox analytics off)");
      return List.of();
    }
    return client.worldIndices().stream()
        .filter(w -> w.changePct() != null)
        .sorted(Comparator.comparing((WorldIndex w) -> w.changePct().abs()).reversed())
        .limit(overnightTopN)
        .map(w -> new GlobalCue(w.name(), w.ltp(), w.changePct()))
        .toList();
  }

  private IndexPriceAction indexPriceAction(OffsetDateTime nowIst, List<String> notes) {
    try {
      CandleQueryService.CandleRead read =
          candles.read(indexExchange, indexSymbol, "1d", nowIst.minusDays(45), nowIst);
      List<Candle> bars = read.items();
      if (bars.size() < 2) {
        notes.add("index price action unavailable (fewer than 2 daily bars for " + indexSymbol + ")");
        return null;
      }
      Candle todayBar = bars.get(bars.size() - 1);
      Candle priorBar = bars.get(bars.size() - 2);
      BigDecimal prevClose = priorBar.close();
      BigDecimal gapOpenPct =
          prevClose == null || prevClose.signum() == 0
              ? null
              : todayBar.open().subtract(prevClose).multiply(HUNDRED).divide(prevClose, 2, RoundingMode.HALF_UP);
      BigDecimal dayRange = todayBar.high().subtract(todayBar.low());
      int window = Math.min(20, bars.size() - 1);
      BigDecimal sum = BigDecimal.ZERO;
      for (int i = bars.size() - 1 - window; i < bars.size() - 1; i++) {
        Candle b = bars.get(i);
        sum = sum.add(b.high().subtract(b.low()));
      }
      BigDecimal avgRange20 =
          window == 0 ? null : sum.divide(BigDecimal.valueOf(window), 4, RoundingMode.HALF_UP);
      BigDecimal rangeVsAvg =
          avgRange20 == null || avgRange20.signum() == 0
              ? null
              : dayRange.divide(avgRange20, 2, RoundingMode.HALF_UP);
      int dir = todayBar.close().compareTo(todayBar.open());
      String direction = dir > 0 ? "UP" : dir < 0 ? "DOWN" : "FLAT";
      String rangeState =
          rangeVsAvg == null
              ? null
              : rangeVsAvg.compareTo(RANGE_WIDE) > 0
                  ? "WIDE"
                  : rangeVsAvg.compareTo(RANGE_NARROW) < 0 ? "NARROW" : "NORMAL";
      return new IndexPriceAction(
          indexSymbol,
          gapOpenPct,
          dayRange,
          avgRange20,
          rangeVsAvg,
          direction,
          rangeState,
          todayBar.bucket().atZoneSameInstant(Ist.ZONE).toLocalDate());
    } catch (RuntimeException e) {
      log.warn("day-context index price action read failed for {}: {}", indexSymbol, e.getMessage());
      notes.add("index price action read failed: " + e.getMessage());
      return null;
    }
  }

  private IngestTrust ingestTrust() {
    IngestHealthBoard.BoardReport board = healthBoard.board(trustLookback);
    List<SourceTrust> sources = new ArrayList<>();
    String overall = "OK";
    for (IngestHealthBoard.SourceHealth s : board.sources()) {
      String status = toTrust(s.status());
      overall = worse(overall, status);
      IngestHealthBoard.LastRun lr = s.lastRun();
      OffsetDateTime lastRunAt =
          lr == null || lr.startedAt() == null
              ? null
              : OffsetDateTime.ofInstant(lr.startedAt(), Ist.ZONE);
      sources.add(
          new SourceTrust(
              s.source(), status, lr == null ? null : lr.status(), lastRunAt, lr != null && lr.stale()));
    }
    return new IngestTrust(overall, List.copyOf(sources));
  }

  /** Map the health board's GREEN/YELLOW/RED verdict to the layer's OK/DEGRADED/BLOCKED trust states. */
  private static String toTrust(String boardStatus) {
    return switch (boardStatus) {
      case "GREEN" -> "OK";
      case "YELLOW" -> "DEGRADED";
      default -> "BLOCKED"; // RED / unknown
    };
  }

  /** The worse of two trust states (BLOCKED > DEGRADED > OK). */
  private static String worse(String a, String b) {
    return rank(b) > rank(a) ? b : a;
  }

  private static int rank(String trust) {
    return switch (trust) {
      case "OK" -> 0;
      case "DEGRADED" -> 1;
      default -> 2; // BLOCKED
    };
  }
}
