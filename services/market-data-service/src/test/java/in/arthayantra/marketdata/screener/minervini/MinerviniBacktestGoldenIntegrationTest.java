package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.screener.minervini.MinerviniBacktestService.BacktestResult;
import in.arthayantra.marketdata.screener.minervini.MinerviniBacktestService.PortfolioStat;
import in.arthayantra.marketdata.screener.minervini.MinerviniBacktestService.Report;
import in.arthayantra.marketdata.testsupport.FixtureShape;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
 * full rationale). The existing {@link MinerviniBacktestBatchEqualityIntegrationTest} only proves
 * batched reads reproduce the serial read WITHIN one run; it would not catch a refactor moving the
 * cost formula, the RS-rank formula, or the exit logic, since both paths would drift together. This
 * pins the ACTUAL headline numbers against a checked-in reference ({@code minervini-golden.json}) —
 * a NEW, independent golden mechanism, untouched by {@code GoldenSignalsJson} or the scalper's
 * {@code exit-equivalence.json}. Unlike the Manas sibling, {@code FixtureShape.variedUptrend} DOES
 * take real Minervini trades at this panel size (its own javadoc: "a run at 60 symbols closes real
 * trades") via the {@code primary-base} setup (a plain new-52-week-high breakout on expanding
 * volume) — Minervini, unlike Manas, has a setup that does not need a detected consolidation base.
 * The panel uses its own symbol prefix ({@value #PREFIX}) so it never collides with the equality IT.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class MinerviniBacktestGoldenIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String PREFIX = "M27MVGOLD";
  private static final int SYMS = 60;
  private static final int DAYS = 600;
  private static final LocalDate END = LocalDate.of(2025, 12, 31);
  private static final LocalDate FROM = LocalDate.of(2025, 6, 1);
  private static final String PRIMARY_VARIANT = "technical";

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

  /** Same deterministic per-symbol-varied panel the F2 equality proof uses (FixtureShape). */
  private void seedSeries(String symbol, int k) {
    List<Object[]> batch = FixtureShape.variedUptrend(symbol, k, DAYS, END);
    jdbc.batchUpdate(
        "INSERT INTO candles(exchange,tradingsymbol,interval,bucket,open,high,low,close,volume,source)"
            + " VALUES('NSE',?, '1d', ?,?,?,?,?,?, 'BACKFILL') ON CONFLICT DO NOTHING",
        batch);
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
