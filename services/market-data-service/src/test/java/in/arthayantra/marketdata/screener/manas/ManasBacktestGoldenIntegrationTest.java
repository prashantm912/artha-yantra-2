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
 * symbols/600 days takes ZERO Manas trades in every variant). So this fixture builds its OWN panel:
 * the same uptrend-then-consolidation-then-volume-spike-breakout-then-rollover shape {@code
 * ManasAroraSwingBacktestTest.breakoutSeries()} already proves takes a real trade, replicated across
 * a panel of {@value #SYMS} symbols.
 *
 * <p><b>Targets the PRODUCTION headline, not a relaxed proxy (audit fix — a first attempt at this
 * golden pinned the RS-relaxed {@code technical} variant's FIFO-gross {@code portfolio()}, which
 * left the RS-rank gate and the net-of-cost math both untested; live/manual runs report {@value
 * #PRIMARY_VARIANT}'s {@code portfolioRsPriorityNet}).</b> The per-symbol uptrend STEEPNESS (not a
 * uniform post-hoc price scale, which cancels out of a % return and produces a degenerate,
 * all-tied RS distribution — measured on an earlier attempt) is varied across the panel so {@code
 * weightedRs}'s trailing-return computation genuinely differentiates symbols, and volume is sized
 * so the 20-day turnover clears the ₹37.5L/day floor {@code rs-turnover-nopyramid} also gates on.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class ManasBacktestGoldenIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String PREFIX = "M27MANASGOLD";
  private static final int SYMS = 30;
  private static final int DAYS = 280;
  private static final LocalDate END = LocalDate.of(2025, 12, 31);
  // FROM = the panel's very first day, so the entry-date gate (`date.isBefore(from)`) never skips a
  // signal — sidesteps the 52-week-lookback trap: a LONGER uptrend before the breakout pushes the
  // 252-day low reference past day 0, diluting the "close >= 2x the 52-week low" gate (measured: a
  // 450-day lead-in took ZERO trades). DAYS=280 keeps the breakout at day 268, same as the proven
  // ManasAroraSwingBacktestTest.breakoutSeries() shape (256 uptrend + 12 consolidation + breakout).
  private static final LocalDate FROM = END.minusDays(DAYS - 1L);
  // The PRODUCTION headline variant (ManasAroraBacktestService.PRIMARY_VARIANT) — RS-rank≥70 AND
  // the turnover floor, single-lot. NOT "technical" (RS/turnover both relaxed): that variant would
  // stay green under a broken RS-rank formula or cost model, which is exactly what this golden must
  // catch (CLAUDE.md: RS-rank is the edge on this family, ~43% CAGR vs ~28% technical-only).
  private static final String PRIMARY_VARIANT = "rs-turnover-nopyramid";

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
    List<Object[]> batch = breakoutPanel(symbol, k, SYMS, DAYS, END);
    jdbc.batchUpdate(
        "INSERT INTO candles(exchange,tradingsymbol,interval,bucket,open,high,low,close,volume,source)"
            + " VALUES('NSE',?, '1d', ?,?,?,?,?,?, 'BACKFILL') ON CONFLICT DO NOTHING",
        batch);
  }

  /**
   * A deterministic, PER-SYMBOL-VARIED replica of {@code ManasAroraSwingBacktestTest.breakoutSeries()}'s
   * proven shape, stretched to {@code days} bars ending on {@code end}: a long uptrend (clears the
   * §4.1 gates) whose END LEVEL grows with {@code k} (180 .. 310 across the panel) — a genuine
   * STEEPNESS gradient, not a uniform post-hoc scale (which cancels out of {@code weightedRs}'s %
   * returns and produces a degenerate, all-tied RS distribution — measured on an earlier attempt
   * that used a uniform {@code 1+0.01k} multiplier and got a 0-trade rs-turnover result). The
   * consolidation and breakout are scaled proportionally to each symbol's own uptrend-end level, so
   * every symbol clears the ENTRY gates identically; only the PRECEDING trend steepness (hence
   * {@code weightedRs}) differs, letting the RS-rank≥70 gate genuinely admit the top performers and
   * reject the rest. Volume is sized so the 20-day turnover clears the ₹37.5L/day floor. Row shape
   * matches {@code FixtureShape.variedUptrend}: {@code {symbol, bucket, open, high, low, close, volume}}.
   */
  private static List<Object[]> breakoutPanel(
      String symbol, int k, int totalSymbols, int days, LocalDate end) {
    double growthFrac = totalSymbols <= 1 ? 0.0 : (double) k / (totalSymbols - 1); // 0..1
    double uptrendEndValue = 180.0 + 130.0 * growthFrac; // 180 (k=0) .. 310 (k=last)
    double consolidationScale = uptrendEndValue / 185.0; // 185 = breakoutSeries()'s own uptrend-end
    double[] close = new double[days];
    long[] volume = new long[days];
    int uptrendEnd = 256; // matches breakoutSeries()'s proven shape (256 uptrend + 12 consolidation)
    long baseVolume = 400_000L; // price(180..310) * 400k clears the ₹37.5L/day turnover floor
    for (int i = 0; i < uptrendEnd; i++) {
      close[i] = 80.0 + (uptrendEndValue - 80.0) * i / (uptrendEnd - 1);
      volume[i] = baseVolume;
    }
    double[] base = {182, 179, 183, 180, 182, 178, 183, 181, 182, 179, 183, 180};
    for (int i = 0; i < base.length; i++) {
      close[uptrendEnd + i] = base[i] * consolidationScale;
      volume[uptrendEnd + i] = baseVolume;
    }
    int breakoutIdx = uptrendEnd + base.length;
    double[] tail = {195, 197, 190, 178, 170};
    for (int i = 0; i < tail.length; i++) {
      close[breakoutIdx + i] = tail[i] * consolidationScale;
      volume[breakoutIdx + i] = i == 0 ? 3L * baseVolume : baseVolume; // 3x expanding-volume gate
    }
    int tailEnd = breakoutIdx + tail.length;
    for (int i = tailEnd; i < days; i++) {
      close[i] = tail[tail.length - 1] * consolidationScale; // flat continuation after the rollover
      volume[i] = baseVolume;
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
    PortfolioStat portfolio = report.portfolioRsPriorityNet();
    assertThat(portfolio)
        .as("the seeded panel must clear the RS-rank + turnover gates to take at least one trade")
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
