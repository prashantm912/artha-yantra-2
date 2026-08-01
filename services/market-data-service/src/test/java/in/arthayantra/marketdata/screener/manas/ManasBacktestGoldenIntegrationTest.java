package in.arthayantra.marketdata.screener.manas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.screener.manas.ManasAroraBacktestService.BacktestResult;
import in.arthayantra.marketdata.screener.manas.ManasAroraBacktestService.PortfolioStat;
import in.arthayantra.marketdata.screener.manas.ManasAroraBacktestService.Report;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M7 (#128 batch scoping) item 3 — a FROZEN-OUTPUT golden for the Manas Arora deep sim. The only
 * existing regression test over this sim, {@link ManasBacktestBatchEqualityIntegrationTest}, is an
 * internal-consistency check (batched reads reproduce the serial read WITHIN the same run) — it
 * would not catch a refactor that moved the cost formula, the RS-rank formula, or the exit logic,
 * because both read paths would drift together and still agree with each other. This test pins the
 * ACTUAL headline numbers ({@link ManasAroraBacktestService#run} over a fixed, seeded panel) against
 * a checked-in reference ({@code manas-golden.json}) — a NEW, independent golden mechanism; it does
 * not touch {@code GoldenSignalsJson} (frozen, live signals/trades only) or the scalper's {@code
 * exit-equivalence.json}. The panel uses its OWN symbol prefix ({@value #PREFIX}) so it never
 * collides with the equality-proof IT's seeded rows in the shared singleton DB.
 *
 * <p>{@code FixtureShape.variedUptrend} (the sibling equality IT's panel) is a SMOOTH exponential
 * curve — it exercises Minervini's simple "new 52-week-high" primary-base setup, but Manas has no
 * such setup: both Manas setups ({@code breakout}/{@code vcp}) need a genuinely detectable
 * consolidation base, which a smooth curve never forms (measured: {@code variedUptrend} at 60
 * symbols/600 days takes ZERO Manas trades in every variant). A golden pinned at all-zero trades
 * would characterize nothing — any refactor to the cost/RS-rank/exit formulas would leave it green.
 * So this fixture builds its OWN panel: the same uptrend-then-consolidation-then-volume-spike-
 * breakout-then-rollover shape {@code ManasAroraSwingBacktestTest.breakoutSeries()} already proves
 * takes a real trade, replicated (with a small per-symbol offset) across a small panel. The
 * {@code technical} variant (RS relaxed) is the target, so cross-sectional RS variety is not needed.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class ManasBacktestGoldenIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String PREFIX = "M27MANASGOLD";
  private static final int SYMS = 5;
  private static final int DAYS = 280;
  private static final LocalDate END = LocalDate.of(2025, 12, 31);
  // FROM = the panel's very first day, so the entry-date gate (`date.isBefore(from)`) never skips a
  // signal — sidesteps the 52-week-lookback trap: a LONGER uptrend before the breakout pushes the
  // 252-day low reference past day 0, diluting the "close >= 2x the 52-week low" gate (measured: a
  // 450-day lead-in took ZERO trades). DAYS=280 keeps the breakout at day 268, same as the proven
  // ManasAroraSwingBacktestTest.breakoutSeries() shape (256 uptrend + 12 consolidation + breakout).
  private static final LocalDate FROM = END.minusDays(DAYS - 1L);
  private static final String PRIMARY_VARIANT = "technical";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ManasAroraBacktestService svc;
  private final ObjectMapper mapper = new ObjectMapper();

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

  private void seedSeries(String symbol, int k) {
    List<Object[]> batch = breakoutPanel(symbol, k, DAYS, END);
    jdbc.batchUpdate(
        "INSERT INTO candles(exchange,tradingsymbol,interval,bucket,open,high,low,close,volume,source)"
            + " VALUES('NSE',?, '1d', ?,?,?,?,?,?, 'BACKFILL') ON CONFLICT DO NOTHING",
        batch);
  }

  /**
   * A deterministic, PER-SYMBOL-offset (by {@code k}) replica of {@code
   * ManasAroraSwingBacktestTest.breakoutSeries()}'s proven shape, stretched to {@code days} bars
   * ending on {@code end}: a long smooth uptrend (clears the §4.1 gates), a 12-day consolidation (a
   * real {@code ConsolidationBreakout} base), a fresh-high breakout on 3x volume, then a rollover —
   * every value a pure function of {@code (k, barIndex)}. Row shape matches {@code
   * FixtureShape.variedUptrend}: {@code {symbol, bucket, open, high, low, close, volume}}.
   */
  private static List<Object[]> breakoutPanel(String symbol, int k, int days, LocalDate end) {
    double offset = 1.0 + 0.01 * k; // tiny per-symbol scale so rows are not byte-identical
    double[] close = new double[days];
    long[] volume = new long[days];
    int uptrendEnd = 256; // matches breakoutSeries()'s proven shape (256 uptrend + 12 consolidation)
    for (int i = 0; i < uptrendEnd; i++) {
      close[i] = (80.0 + 105.0 * i / (uptrendEnd - 1)) * offset;
      volume[i] = 1_000L;
    }
    double[] base = {182, 179, 183, 180, 182, 178, 183, 181, 182, 179, 183, 180};
    for (int i = 0; i < base.length; i++) {
      close[uptrendEnd + i] = base[i] * offset;
      volume[uptrendEnd + i] = 1_000L;
    }
    int breakoutIdx = uptrendEnd + base.length;
    double[] tail = {195, 197, 190, 178, 170};
    for (int i = 0; i < tail.length; i++) {
      close[breakoutIdx + i] = tail[i] * offset;
      volume[breakoutIdx + i] = i == 0 ? 3_000L : 1_000L; // 3x expanding-volume gate on the break bar
    }
    int tailEnd = breakoutIdx + tail.length;
    for (int i = tailEnd; i < days; i++) {
      close[i] = tail[tail.length - 1] * offset; // flat continuation after the rollover
      volume[i] = 1_000L;
    }

    List<Object[]> rows = new ArrayList<>(days);
    for (int i = 0; i < days; i++) {
      LocalDate day = end.minusDays(days - 1L - i);
      Timestamp bucket = Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant());
      double price = Math.round(close[i] * 100.0) / 100.0; // flat OHLC (O=H=L=C), matches breakoutSeries()
      rows.add(new Object[] {symbol, bucket, price, price, price, price, volume[i]});
    }
    return rows;
  }

  @Test
  void headlineStatsMatchTheFrozenGolden() throws Exception {
    BacktestResult result = svc.run(FROM); // the SAME production entry point live/manual runs use

    assertThat(result.status()).isEqualTo("completed");
    Report report =
        result.variants().stream()
            .filter(r -> r.variant().equals(PRIMARY_VARIANT))
            .findFirst()
            .orElseThrow();
    PortfolioStat portfolio = report.portfolio();
    assertThat(portfolio)
        .as("the seeded panel must clear enough gates to take at least one trade")
        .isNotNull();

    JsonNode golden = readGolden();
    // Printed unconditionally so a future INTENTIONAL doctrine change has the new numbers ready to
    // paste into the golden file, without re-deriving them by hand.
    System.out.println(
        "M27-GOLDEN[Manas] totalTrades=" + report.totalTrades()
            + " tradesTaken=" + portfolio.tradesTaken()
            + " cagrPct=" + portfolio.cagrPct()
            + " maxDrawdownPct=" + portfolio.maxDrawdownPct()
            + " sharpe=" + portfolio.sharpe());

    assertThat(report.totalTrades()).as("totalTrades").isEqualTo(golden.path("totalTrades").asInt());
    assertThat(portfolio.tradesTaken()).as("tradesTaken").isEqualTo(golden.path("tradesTaken").asInt());
    assertThat(portfolio.cagrPct())
        .as("cagrPct")
        .isEqualByComparingTo(new BigDecimal(golden.path("cagrPct").asText()));
    assertThat(portfolio.maxDrawdownPct())
        .as("maxDrawdownPct")
        .isEqualByComparingTo(new BigDecimal(golden.path("maxDrawdownPct").asText()));
    assertThat(portfolio.sharpe())
        .as("sharpe")
        .isEqualByComparingTo(new BigDecimal(golden.path("sharpe").asText()));
  }

  private JsonNode readGolden() throws Exception {
    // surefire's working dir is the module dir.
    Path p = Path.of("src", "test", "resources", "screener", "swing-golden", "manas-golden.json");
    return mapper.readTree(Files.readString(p));
  }
}
