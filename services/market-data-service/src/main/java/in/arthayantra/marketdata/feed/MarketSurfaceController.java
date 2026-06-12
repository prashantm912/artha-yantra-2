package in.arthayantra.marketdata.feed;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The small Phase-17 market surface: last ticks + the calendar status (B-1). */
@RestController
@RequestMapping("/api/v1/market")
public class MarketSurfaceController {

  private final LastTickStore lastTickStore;
  private final MarketCalendar calendar;
  private final Clock clock;

  /** Wires the read paths. */
  public MarketSurfaceController(LastTickStore lastTickStore, MarketCalendar calendar, Clock clock) {
    this.lastTickStore = lastTickStore;
    this.calendar = calendar;
    this.clock = clock;
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
  public Map<String, Object> status() {
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(Ist.OFFSET);
    LocalDate today = now.toLocalDate();
    boolean tradingDay;
    boolean open;
    LocalDate nextTradingDay;
    try {
      tradingDay = calendar.isTradingDay(today);
      open = calendar.isOpen(now.toInstant());
      nextTradingDay = calendar.nextTradingDay(today);
    } catch (IllegalArgumentException uncoveredYear) {
      tradingDay = false;
      open = false;
      nextTradingDay = null;
    }
    return Map.of(
        "serverTime", now.toString(),
        "tradingDay", tradingDay,
        "marketOpen", open,
        "sessionOpen", MarketCalendar.SESSION_OPEN.toString(),
        "sessionClose", MarketCalendar.SESSION_CLOSE.toString(),
        "nextTradingDay", nextTradingDay == null ? "" : nextTradingDay.toString());
  }
}
