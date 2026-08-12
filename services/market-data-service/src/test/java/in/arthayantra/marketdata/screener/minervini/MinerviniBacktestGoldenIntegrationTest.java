package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.screener.minervini.MinerviniBacktestService.BacktestResult;
import in.arthayantra.marketdata.screener.minervini.MinerviniBacktestService.PortfolioStat;
import in.arthayantra.marketdata.screener.minervini.MinerviniBacktestService.Report;
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
 * M7 (#128 batch scoping) item 3 — a FROZEN-OUTPUT golden for the Minervini deep sim. Sibling of
 * {@link ManasBacktestGoldenIntegrationTest} in the {@code manas} package (see its javadoc for the
 * full rationale, incl. why a uniform per-symbol price SCALE cannot create real RS-rank variety).
 * The existing {@link MinerviniBacktestBatchEqualityIntegrationTest} only proves batched reads
 * reproduce the serial read WITHIN one run; it would not catch a refactor moving the cost formula,
 * the RS-rank formula, or the exit logic, since both paths would drift together.
 *
 * <p><b>Targets the PRODUCTION headline, not a relaxed proxy (audit fix — a first attempt at this
 * golden pinned the RS-relaxed {@code technical} variant's FIFO-gross {@code portfolio()} over
 * {@code FixtureShape.variedUptrend}, which measured ZERO trades for {@code rs-only}/{@code
 * rs-turnover} despite variedUptrend's per-symbol drift, so the RS-rank gate and the net-of-cost
 * math both stayed untested; live/manual runs report {@value #PRIMARY_VARIANT}'s {@code
 * portfolioRsPriorityNet}).</b> This fixture instead builds its OWN panel: the same
 * uptrend-then-consolidation-then-volume-spike-breakout-then-rollover shape {@code
 * MinerviniSwingBacktestTest.primaryBaseTakesABreakoutTradeAndStopsOutOnTheRollover} already proves
 * takes a real primary-base trade, with a genuine per-symbol uptrend-STEEPNESS gradient (not a
 * post-hoc scale, which cancels out of a % return) so {@code weightedRs} actually differentiates
 * the panel, and volume sized so the 20-day turnover clears the ₹37.5L/day floor.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class MinerviniBacktestGoldenIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String PREFIX = "M27MVGOLD";
  private static final int SYMS = 30;
  private static final int DAYS = 280;
  private static final LocalDate END = LocalDate.of(2025, 12, 31);
  private static final LocalDate FROM = END.minusDays(DAYS - 1L);
  // The PRODUCTION headline variant (MinerviniBacktestService.PRIMARY_VARIANT) — RS-rank>=70 AND
  // the turnover floor. NOT "technical" (both relaxed): CLAUDE.md's own measurement is that
  // RS-rank is the edge on this family (rs-only ~43% CAGR vs ~28% technical), so a golden pinned on
  // the relaxed variant would guard the path nobody actually trades.
  private static final String PRIMARY_VARIANT = "rs-turnover";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private MinerviniBacktestService svc;
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
    List<Object[]> batch = primaryBasePanel(symbol, k, SYMS, DAYS, END);
    jdbc.batchUpdate(
        "INSERT INTO candles(exchange,tradingsymbol,interval,bucket,open,high,low,close,volume,source)"
            + " VALUES('NSE',?, '1d', ?,?,?,?,?,?, 'BACKFILL') ON CONFLICT DO NOTHING",
        batch);
  }

  /**
   * A deterministic, PER-SYMBOL-VARIED replica of {@code
   * MinerviniSwingBacktestTest.primaryBaseTakesABreakoutTradeAndStopsOutOnTheRollover}'s proven
   * shape: a Stage-2 uptrend (100 -> uptrendEndValue) whose END LEVEL grows with {@code k} (170 ..
   * 300 across the panel) — a genuine steepness gradient so {@code weightedRs}'s trailing % returns
   * differentiate the panel (a uniform post-hoc scale cancels out and was measured to produce a
   * degenerate distribution) — an 8-day consolidation and a fresh-52w-high breakout scaled
   * proportionally to each symbol's own uptrend-end level, then a rollover. Volume is sized so the
   * 20-day turnover clears the ₹37.5L/day floor. Row shape matches {@code
   * FixtureShape.variedUptrend}: {@code {symbol, bucket, open, high, low, close, volume}}.
   */
  private static List<Object[]> primaryBasePanel(
      String symbol, int k, int totalSymbols, int days, LocalDate end) {
    double growthFrac = totalSymbols <= 1 ? 0.0 : (double) k / (totalSymbols - 1); // 0..1
    double uptrendEndValue = 170.0 + 130.0 * growthFrac; // 170 (k=0) .. 300 (k=last)
    double scale = uptrendEndValue / 180.0; // 180 = primaryBaseTakesABreakoutTrade...'s uptrend-end
    double[] close = new double[days];
    long[] volume = new long[days];
    int uptrendEnd = 256;
    long baseVolume = 400_000L; // price(170..300) * 400k clears the ₹37.5L/day turnover floor
    for (int i = 0; i < uptrendEnd; i++) {
      close[i] = 100.0 + (uptrendEndValue - 100.0) * i / (uptrendEnd - 1);
      volume[i] = baseVolume;
    }
    double[] base = {175, 173, 176, 174, 175, 173, 176, 174};
    for (int i = 0; i < base.length; i++) {
      close[uptrendEnd + i] = base[i] * scale;
      volume[uptrendEnd + i] = baseVolume;
    }
    int breakoutIdx = uptrendEnd + base.length;
    double[] tail = {185, 188, 182, 174, 165};
    for (int i = 0; i < tail.length; i++) {
      close[breakoutIdx + i] = tail[i] * scale;
      volume[breakoutIdx + i] = i == 0 ? 3L * baseVolume : baseVolume; // 3x expanding-volume gate
    }
    int tailEnd = breakoutIdx + tail.length;
    for (int i = tailEnd; i < days; i++) {
      close[i] = tail[tail.length - 1] * scale;
      volume[i] = baseVolume;
    }

    List<Object[]> rows = new ArrayList<>(days);
    for (int i = 0; i < days; i++) {
      LocalDate day = end.minusDays(days - 1L - i);
      Timestamp bucket = Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant());
      double price = Math.round(close[i] * 100.0) / 100.0;
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
    PortfolioStat portfolio = report.portfolioRsPriorityNet();
    assertThat(portfolio)
        .as("the seeded panel must clear the RS-rank + turnover gates to take at least one trade")
        .isNotNull();

    JsonNode golden = readGolden();
    System.out.println(
        "M27-GOLDEN[Minervini] totalTrades=" + report.totalTrades()
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
    Path p = Path.of("src", "test", "resources", "screener", "swing-golden", "minervini-golden.json");
    return mapper.readTree(Files.readString(p));
  }
}
