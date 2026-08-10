package in.arthayantra.marketdata.nse;

import in.arthayantra.marketcalendar.MarketCalendar;
import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.nse.BhavcopyFetcher.BhavcopyRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Parses the spike-captured NSE sec_bhavdata_full CSV (leading-space cells, "-" non-deliv rows). */
class LiveBhavcopyFetcherTest {

  private static final String CSV =
      "SYMBOL, SERIES, DATE1, PREV_CLOSE, OPEN_PRICE, HIGH_PRICE, LOW_PRICE, LAST_PRICE,"
          + " CLOSE_PRICE, AVG_PRICE, TTL_TRD_QNTY, TURNOVER_LACS, NO_OF_TRADES, DELIV_QTY,"
          + " DELIV_PER\n"
          + "360ONE, EQ, 12-Jun-2026, 1064.10, 1079.70, 1099.00, 1058.00, 1094.00, 1096.80,"
          + " 1082.08, 696601, 7537.75, 33351, 336020, 48.24\n"
          + "AAKASH, BE, 12-Jun-2026, 10.00, 10.20, 10.50, 10.05, 10.30, 10.30, 10.27, 219779,"
          + " 22.58, 444, -, -\n";

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-06-15T13:30:00Z"), ZoneOffset.UTC);

  @Test
  void parsesSecBhavdataFullCsv() {
    BhavcopyFetcher fetcher =
        new LiveBhavcopyFetcher(
            new StubClient(CSV), "https://archives", CLOCK, new SimpleMeterRegistry(), MarketCalendar.nse());

    List<BhavcopyRow> rows = fetcher.fetchLatest();

    assertThat(rows).hasSize(2);

    BhavcopyRow eq = rows.stream().filter(r -> r.symbol().equals("360ONE")).findFirst().orElseThrow();
    assertThat(eq.series()).isEqualTo("EQ");
    assertThat(eq.date()).isEqualTo(LocalDate.of(2026, 6, 12));
    assertThat(eq.open()).isEqualByComparingTo("1079.70");
    assertThat(eq.last()).isEqualByComparingTo("1094.00");
    assertThat(eq.close()).isEqualByComparingTo("1096.80");
    assertThat(eq.totalTradedQty()).isEqualTo(696601L);
    assertThat(eq.noOfTrades()).isEqualTo(33351L);
    assertThat(eq.delivQty()).isEqualTo(336020L);
    assertThat(eq.delivPer()).isEqualByComparingTo("48.24");

    BhavcopyRow be = rows.stream().filter(r -> r.symbol().equals("AAKASH")).findFirst().orElseThrow();
    assertThat(be.series()).isEqualTo("BE");
    assertThat(be.close()).isEqualByComparingTo("10.30");
    assertThat(be.delivQty()).isNull();
    assertThat(be.delivPer()).isNull();
  }

  /**
   * NSE answers 200 with the PREVIOUS trading day's file under most holiday URLs, and the row's
   * trade date comes from the CSV's own DATE1 column — so an unchecked payload is stored under
   * H-1 while the requested day H stays missing and is re-probed (and H-1 re-stamped) forever.
   */
  @Test
  void refusesAPayloadDatedForADifferentDayThanTheOneRequested() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    BhavcopyFetcher fetcher =
        new LiveBhavcopyFetcher(
            new StubClient(CSV), "https://archives", CLOCK, registry, MarketCalendar.nse());

    // The stub serves the same 12-Jun rows for EVERY archive URL.
    assertThat(fetcher.fetchForDate(LocalDate.of(2026, 6, 15))).isEmpty();
    assertThat(fetcher.fetchForDate(LocalDate.of(2026, 6, 12))).hasSize(2);

    // The refusal is otherwise INVISIBLE downstream: the backfill still records a SUCCESS ingest run,
    // so a systematic false positive would stall the feed forever and still report green. The counter
    // is the only signal that distinguishes "the guard is refusing" from "the fetch came back empty".
    // Registered by the FETCHER, not by this test — the assertion reads production's own meter.
    assertThat(misdated(registry))
        .as("one refusal, and exactly one — the correctly-dated fetch must not increment it")
        .isEqualTo(1.0);
  }

  /**
   * ⚠️ The counter must stay at ZERO on a holiday, and this is the assertion that makes the metric
   * usable at all (review of #1329). Serving the previous day's file under a holiday URL is NORMAL
   * NSE behaviour — it is the exact case the guard was written for. The catch-up loop skips only
   * Saturdays, Sundays and dates already stored, so every exchange holiday inside the window is
   * re-probed on EVERY run and refused every time. Counting those would give the counter a
   * monotonically-rising baseline on a perfectly healthy feed, and no threshold could then separate
   * it from the systematic break it exists to reveal. 2026-06-26 is Muharram in the bundled NSE
   * calendar; the guard still REFUSES the payload (behaviour unchanged) — only the count is gated.
   */
  @Test
  void aHolidayRefusalIsNotCounted() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    BhavcopyFetcher fetcher =
        new LiveBhavcopyFetcher(
            new StubClient(CSV), "https://archives", CLOCK, registry, MarketCalendar.nse());

    assertThat(fetcher.fetchForDate(LocalDate.of(2026, 6, 26)))
        .as("the guard still refuses the mis-dated payload — only the COUNT is calendar-gated")
        .isEmpty();

    assertThat(misdated(registry))
        .as("a holiday's re-served file is healthy NSE behaviour and must not raise the counter")
        .isEqualTo(0.0);
  }

  private static double misdated(SimpleMeterRegistry registry) {
    return registry.get("ay_bhavcopy_misdated_payload_total").tag("exchange", "NSE").counter().count();
  }

  /** NseHttpClient stub returning canned CSV for any archive URL. */
  static class StubClient extends NseHttpClient {
    private final String body;

    StubClient(String body) {
      super(RestClient.builder(), "https://x");
      this.body = body;
    }

    @Override
    public String getAbsolute(String url) {
      return body;
    }
  }
}
