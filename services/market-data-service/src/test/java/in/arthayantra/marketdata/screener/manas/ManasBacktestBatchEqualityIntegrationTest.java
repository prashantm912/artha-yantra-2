package in.arthayantra.marketdata.screener.manas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.screener.manas.ManasAroraBacktestService.BacktestResult;
import in.arthayantra.marketdata.screener.manas.ManasAroraBacktestService.Series;
import in.arthayantra.marketdata.screener.minervini.geometry.DailyBar;
import in.arthayantra.marketdata.testsupport.FixtureShape;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * F2 EQUALITY PROOF (Manas Arora deep-sim). Seeds a deterministic {@value #SYMS}-symbol daily panel
 * in {@code candles}@1d (more than one {@code READ_CHUNK} page, so the batched reader's chunk
 * boundary + cross-chunk symbol ordering are exercised) and runs {@link ManasAroraBacktestService}
 * over the SAME seeded DB state via BOTH read strategies:
 *
 * <ul>
 *   <li>the production CHUNK-BATCHED readers ({@code svc.run(FROM)} — one query per page), and
 *   <li>the per-symbol SERIAL readers ({@code svc.run(FROM, serial…)} — the pre-F2 one-query-per-
 *       symbol path, still present as {@link ManasAroraBacktestService#readCloses}/{@code readSeries}).
 * </ul>
 *
 * The full {@link BacktestResult} (per-variant trade stats + all portfolio equity runs + slot sweep)
 * must be byte-identical after normalising only the wall-clock {@code runAt} stamp — an unchanged
 * sim re-runs to the decimal, so any diff would mean the batching changed the bars a symbol sees or
 * their order. The batched read is also asserted to reproduce the serial per-symbol series exactly.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class ManasBacktestBatchEqualityIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String PREFIX = "F2MANAS";
  private static final int SYMS = 60; // > READ_CHUNK(50) → two batched pages
  private static final int DAYS = 600;
  private static final LocalDate END = LocalDate.of(2025, 12, 31);
  // warmStart = FROM.minusDays(600) = 2023-10-13, before the series start → all bars read; the trade
  // window (FROM..END ≈ 214 sessions) leaves room for entries to close.
  private static final LocalDate FROM = LocalDate.of(2025, 6, 1);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ManasAroraBacktestService svc;
  @Autowired private ObjectMapper mapper;

  private static List<String> symbols() {
    List<String> out = new ArrayList<>(SYMS);
    for (int i = 0; i < SYMS; i++) {
      out.add(PREFIX + String.format("%02d", i));
    }
    return out;
  }

  private void purge() {
    for (String s : symbols()) {
      jdbc.update("DELETE FROM candles WHERE tradingsymbol=?", s);
      jdbc.update("DELETE FROM instruments WHERE tradingsymbol=?", s);
    }
  }

  @AfterEach
  void tearDown() {
    purge();
  }

  @BeforeEach
  void seed() {
    purge();
    List<String> syms = symbols();
    for (int k = 0; k < syms.size(); k++) {
      String s = syms.get(k);
      jdbc.update(
          "INSERT INTO instruments(exchange,tradingsymbol,name,instrument_type,is_active)"
              + " VALUES('NSE',?,?, 'EQ', true) ON CONFLICT DO NOTHING",
          s, s);
      seedSeries(s, k);
    }
  }

  /**
   * A deterministic per-symbol-varied rising panel (see {@link FixtureShape#variedUptrend}) — every
   * value is a pure function of {@code (symbol index, bar index)}, so both read strategies see
   * identical bars and the equality is byte-reproducible.
   */
  private void seedSeries(String symbol, int k) {
    List<Object[]> batch = FixtureShape.variedUptrend(symbol, k, DAYS, END);
    jdbc.batchUpdate(
        "INSERT INTO candles(exchange,tradingsymbol,interval,bucket,open,high,low,close,volume,source)"
            + " VALUES('NSE',?, '1d', ?,?,?,?,?,?, 'BACKFILL') ON CONFLICT DO NOTHING",
        batch);
  }

  @Test
  void batchedReadsReproduceTheSerialBacktestToTheDecimal() throws Exception {
    // Serial reader lambdas = the pre-F2 per-symbol path (one query per symbol), shaped into the
    // chunk-map the run() seam consumes.
    BiFunction<List<String>, LocalDate, Map<String, Series>> serialCloses =
        (chunk, f) -> {
          Map<String, Series> m = new LinkedHashMap<>();
          for (String s : chunk) {
            m.put(s, svc.readCloses(s, f));
          }
          return m;
        };
    BiFunction<List<String>, LocalDate, Map<String, List<DailyBar>>> serialBars =
        (chunk, f) -> {
          Map<String, List<DailyBar>> m = new LinkedHashMap<>();
          for (String s : chunk) {
            m.put(s, svc.readSeries(s, f));
          }
          return m;
        };

    long t0 = System.nanoTime();
    BacktestResult batched = svc.run(FROM); // production CHUNK-BATCHED path
    long batchedMs = (System.nanoTime() - t0) / 1_000_000;
    long t1 = System.nanoTime();
    BacktestResult serial = svc.run(FROM, serialCloses, serialBars); // pre-F2 per-symbol path
    long serialMs = (System.nanoTime() - t1) / 1_000_000;
    // Local Testcontainers timing (no pg_dump contention / network latency) understates the production
    // win; the load-bearing metric is the round-trip COUNT: ~2×N per-symbol reads → ~2×⌈N/READ_CHUNK⌉.
    System.out.println(
        "F2-TIMING[Manas] serialMs=" + serialMs + " batchedMs=" + batchedMs + " (READ_CHUNK=50, N=" + SYMS + ")");

    // Sanity: the sim actually ran over the seeded panel (not a trivial both-empty match).
    assertThat(batched.status()).isEqualTo("completed");
    int scanned = batched.variants().get(0).symbolsScanned();
    assertThat(scanned).as("all seeded symbols cleared MIN_SERIES and were scanned").isGreaterThanOrEqualTo(SYMS);

    String batchedJson = normalize(batched);
    String serialJson = normalize(serial);
    // verbatim before/after for the F2 receipt
    System.out.println("F2-EQUALITY[Manas] scanned=" + scanned);
    System.out.println("F2-EQUALITY[Manas] serial(before)=" + serialJson);
    System.out.println("F2-EQUALITY[Manas] batched(after)=" + batchedJson);
    assertThat(batchedJson)
        .as("CHUNK-BATCHED reads reproduce the per-symbol serial backtest byte-for-byte (runAt aside)")
        .isEqualTo(serialJson);

    // Direct read-level proof: the BATCHED page reader reproduces each symbol's per-symbol serial
    // series exactly (same bars, same order) across a > READ_CHUNK slice.
    List<String> page = symbols(); // all 60 in one IN(…) page
    LocalDate warmStart = FROM.minusDays(600);
    Map<String, List<DailyBar>> batchedPage = svc.readSeriesBatched(page, warmStart);
    for (String s : page) {
      assertThat(batchedPage.get(s))
          .as("batched bars for %s equal the serial per-symbol read", s)
          .isEqualTo(svc.readSeries(s, warmStart));
    }
  }

  private String normalize(BacktestResult r) throws Exception {
    String json = mapper.writeValueAsString(r);
    return r.runAt() == null ? json : json.replace(r.runAt(), "RUNAT");
  }
}
