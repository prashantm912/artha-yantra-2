package in.arthayantra.marketdata.feed;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.kite.GlobalQuoteSource;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The small Phase-17 market surface: last ticks + the calendar status (B-1) + the INDIA VIX quote. */
@RestController
@RequestMapping("/api/v1/market")
public class MarketSurfaceController {

  /** INDIA VIX is an ordinary pinned NSE index instrument (FP-14) — no special casing. */
  private static final InstrumentKey VIX_KEY = new InstrumentKey("NSE", "INDIA VIX");
  /** The Dow global cue — OpenAlgo {@code DOWJONES@GLOBAL_INDEX}, the same key the Connecting-Dots uses. */
  private static final InstrumentKey DOW_KEY = new InstrumentKey("GLOBAL_INDEX", "DOWJONES");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final LastTickStore lastTickStore;
  private final MarketCalendar calendar;
  private final QuoteGateway quoteGateway;
  private final org.springframework.beans.factory.ObjectProvider<GlobalQuoteSource> dowSource;
  private final Clock clock;

  /** Wires the read paths. */
  public MarketSurfaceController(
      LastTickStore lastTickStore,
      MarketCalendar calendar,
      QuoteGateway quoteGateway,
      org.springframework.beans.factory.ObjectProvider<GlobalQuoteSource> dowSource,
      Clock clock) {
    this.lastTickStore = lastTickStore;
    this.calendar = calendar;
    this.quoteGateway = quoteGateway;
    this.dowSource = dowSource;
    this.clock = clock;
  }

  /** The Dow global-cue quote: LTP + prev close + signed direction (+1 up / −1 down / 0 flat). */
  public record DowQuote(
      BigDecimal ltp,
      @Schema(types = {"number", "null"}) BigDecimal prevClose,
      @Schema(types = {"number", "null"}) BigDecimal change,
      @Schema(types = {"integer", "null"}) Integer direction) {}

  /**
   * GET /global/dow: the Dow Jones global cue (LTP-direction vs prev close). 422 DATA_GAP when the
   * global-quote feed is unconfigured (absent unless one of {@code artha.openalgo.global-quotes-enabled}
   * / {@code artha.upstox.global-quotes-enabled} is on) or no USABLE quote exists (off-hours / mock,
   * but also a quote missing a positive LTP or a prev close — see {@link GlobalQuoteSource#latest},
   * which refuses those rather than handing on a value that cannot produce a direction). The scalper
   * Dow read then degrades to neutral. Every such refusal is counted as
   * {@code ay_global_quote_degraded_total}, so a permanently-422 Dow is diagnosable from metrics alone.
   */
  @GetMapping("/global/dow")
  public DowQuote dow() {
    GlobalQuoteSource source = dowSource.getIfAvailable();
    QuoteGateway.Quote q = source == null ? null : source.latest(DOW_KEY).orElse(null);
    if (q == null || q.lastPrice() == null) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no Dow global-cue quote");
    }
    QuoteGateway.Quote.Ohlc o = q.ohlc(); // globals are LTP-only → the OHLC close slot is the prev close
    BigDecimal prevClose = o == null ? null : o.close();
    BigDecimal change = prevClose == null ? null : q.lastPrice().subtract(prevClose);
    Integer direction = change == null ? null : change.signum();
    return new DowQuote(q.lastPrice(), prevClose, change, direction);
  }

  /** INDIA VIX quote: LTP + day OHLC + change vs the previous close (the §20.7.4 header VIX). */
  public record VixQuote(
      BigDecimal ltp,
      @Schema(types = {"number", "null"}) BigDecimal dayHigh,
      @Schema(types = {"number", "null"}) BigDecimal dayLow,
      @Schema(types = {"number", "null"}) BigDecimal dayOpen,
      @Schema(types = {"number", "null"}) BigDecimal prevClose,
      @Schema(types = {"number", "null"}) BigDecimal change,
      @Schema(types = {"number", "null"}) BigDecimal changePct,
      OffsetDateTime asOf) {}

  /** GET /vix: the INDIA VIX quote (the pinned index); 422 DATA_GAP when no quote (off-hours / mock). */
  @GetMapping("/vix")
  public VixQuote vix() {
    QuoteGateway.Quote q = quoteGateway.quotes(List.of(VIX_KEY)).get(VIX_KEY);
    if (q == null || q.lastPrice() == null) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no INDIA VIX quote");
    }
    QuoteGateway.Quote.Ohlc o = q.ohlc();
    BigDecimal prevClose = o == null ? null : o.close();
    BigDecimal change = prevClose == null ? null : q.lastPrice().subtract(prevClose);
    BigDecimal changePct =
        prevClose == null || prevClose.signum() == 0
            ? null
            : change.multiply(HUNDRED).divide(prevClose, 4, RoundingMode.HALF_UP);
    return new VixQuote(
        q.lastPrice(),
        o == null ? null : o.high(),
        o == null ? null : o.low(),
        o == null ? null : o.open(),
        prevClose,
        change,
        changePct,
        q.timestamp());
  }

  /** The conflated last-tick MAP, optionally filtered by a symbols CSV (B-1). */
  @GetMapping("/ticks/latest")
  public Map<String, NormalizedTick> latestTicks(
      @org.springframework.web.bind.annotation.RequestParam(required = false) String symbols) {
    java.util.Set<String> wanted =
        symbols == null || symbols.isBlank()
            ? null
            : java.util.Set.of(symbols.split(",", -1));
    Map<String, NormalizedTick> out = new java.util.TreeMap<>();
    lastTickStore
        .snapshot()
        .forEach(
            (key, tick) -> {
              String canonical = key.canonical();
              if (wanted == null || wanted.contains(canonical)) {
                out.put(canonical, tick);
              }
            });
    return out;
  }

  /** Calendar status: session open/closed, trading day, next trading day. */
  @GetMapping("/status")
  public MarketSurfaceStatus status() {
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(Ist.OFFSET);
    LocalDate today = now.toLocalDate();
    boolean tradingDay;
    boolean open;
    LocalDate nextTradingDay;
    LocalDate previousTradingDay;
    LocalDate lastTradingDay;
    try {
      tradingDay = calendar.isTradingDay(today);
      open = calendar.isOpen(now.toInstant());
      nextTradingDay = calendar.nextTradingDay(today);
      previousTradingDay = calendar.previousTradingDay(today);
      // The most recent completed (or current) session — the sensible default date for history pages
      // so weekends/holidays don't land on an empty day.
      lastTradingDay = tradingDay ? today : previousTradingDay;
    } catch (IllegalArgumentException uncoveredYear) {
      tradingDay = false;
      open = false;
      nextTradingDay = null;
      previousTradingDay = null;
      lastTradingDay = null;
    }
    return new MarketSurfaceStatus(
        now.toString(),
        tradingDay,
        open,
        MarketCalendar.SESSION_OPEN.toString(),
        MarketCalendar.SESSION_CLOSE.toString(),
        nextTradingDay == null ? "" : nextTradingDay.toString(),
        previousTradingDay == null ? "" : previousTradingDay.toString(),
        lastTradingDay == null ? "" : lastTradingDay.toString());
  }

  /**
   * Calendar surface for the SPA. Every date is the {@code LocalDate.toString()} STRING, and the
   * uncovered-year fallback emits {@code ""} — never null — so all three stay non-nullable. The
   * pre-D3 {@code Map.of} would have thrown on a null, which is exactly why those ternaries exist.
   */
  public record MarketSurfaceStatus(
      String serverTime,
      boolean tradingDay,
      boolean marketOpen,
      String sessionOpen,
      String sessionClose,
      String nextTradingDay,
      String previousTradingDay,
      String lastTradingDay) {}

  /**
   * NSE trading holidays the bundled calendar covers, date-ascending. Each row carries the ISO date, the
   * weekday name, and the published description; the Passed/Coming "validity" is derived client-side vs
   * today. Map-envelope (springdoc does not enumerate it); the calendar covers only the resource's years.
   */
  @GetMapping("/holidays")
  public HolidaysResponse holidays() {
    List<HolidayEntry> items =
        calendar.holidayList().stream()
            .map(
                h ->
                    new HolidayEntry(
                        h.date().toString(),
                        h.date().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                        h.name()))
            .toList();
    return new HolidaysResponse(
        items, OffsetDateTime.now(clock).withOffsetSameInstant(Ist.OFFSET).toString());
  }

  /**
   * One holiday row. ⚠️ The ITEM was itself an anonymous {@code Map<String,Object>}, so it published
   * as a bare object and NOTHING about a holiday was enumerated — the exact blindness this
   * conversion removes. All three components are computed, non-null.
   */
  public record HolidayEntry(String date, String day, String description) {}

  /** The {items, asOf} envelope. Multi-key {@code Map.of} before D3 — order NORMALISED. */
  public record HolidaysResponse(List<HolidayEntry> items, String asOf) {}
}
