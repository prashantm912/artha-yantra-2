package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.BarValues;
import in.arthayantra.strategyengine.eval.CompositeScorer;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The E11 PE-mirror direction-awareness fix (owner 2026-07-20). Every {@code -pe} scalper's CHART
 * composite scores {@code rsi14} + {@code supertrend}; the bug was that both normalizers were BULLISH
 * (byte-copied from the CE twin), so a PUT — whose gate is bearish ({@code close < vwap},
 * {@code supertrend < 0}) — could clear the composite ONLY on a bullish bar the gate can never admit,
 * leaving all 27 structurally dead (0 fires ever, verified live). The fix inverts the two normalizers
 * for the {@code -pe} configs only ({@code rsi_momentum} → {@code linear 50→30};
 * {@code direction} → {@code step[{upto:0,score:1},{score:0}]}).
 *
 * <p>This proves, against the SHIPPED classpath YAML, that each PE now scores HIGH on a bearish bar
 * (SuperTrend down, low RSI) and LOW on a bullish bar — the exact MIRROR of its CE twin. It exercises
 * the real {@link CompositeScorer} over the compiled definition; the bank feeds raw indicator values
 * that the normalize specs map. Scope: the CHART composite (threshold 0.2) only — the separate 0.600
 * scalper confluence gate is not exercised here.
 */
class ScalperPeCompositeInversionTest {

  // 9 directional families × {NIFTY, SENSEX·NIFTY-OI, SENSEX·SENSEX-OI} = the 27 PE mirrors.
  private static final List<String> FAMILIES =
      List.of(
          "connect-the-dots",
          "gap-theory",
          "golden-crossover",
          "market-movers",
          "morning-trade",
          "open-high-low",
          "trend-change",
          "trending-oi",
          "two-candle");
  private static final List<String> VARIANTS =
      List.of("nifty", "sensex-niftyoi", "sensex-sensexoi");

  // A decisively BEARISH bar (oversold RSI + SuperTrend downtrend) and a decisively BULLISH bar.
  private static final BigDecimal RSI_BEAR = new BigDecimal("25");
  private static final BigDecimal RSI_BULL = new BigDecimal("75");
  private static final BigDecimal ST_DOWN = new BigDecimal("-1"); // Ta4j supertrendDirection: -1 down
  private static final BigDecimal ST_UP = new BigDecimal("1"); //                                +1 up

  @Test
  void everyPeCompositeScoresBearishHighBullishLow_theMirrorOfItsCeTwin() throws IOException {
    for (String family : FAMILIES) {
      for (String variant : VARIANTS) {
        String ce = "scalp-" + family + "-" + variant;
        String pe = ce + "-pe";

        BigDecimal peBear = composite(pe, RSI_BEAR, ST_DOWN);
        BigDecimal peBull = composite(pe, RSI_BULL, ST_UP);
        BigDecimal threshold = threshold(pe);

        // PE: bearish bar clears the composite, bullish bar does not — the whole point of the fix.
        assertThat(peBear).as(pe + " chart composite HIGH on a bearish bar").isEqualByComparingTo("1.0");
        assertThat(peBull).as(pe + " chart composite LOW on a bullish bar").isEqualByComparingTo("0");
        assertThat(peBear.compareTo(threshold) >= 0)
            .as(pe + " bearish composite clears its own threshold " + threshold)
            .isTrue();
        assertThat(peBull.compareTo(threshold) < 0)
            .as(pe + " bullish composite misses its own threshold " + threshold)
            .isTrue();

        // CE twin is the exact mirror and remains untouched: bullish HIGH, bearish LOW.
        BigDecimal ceBear = composite(ce, RSI_BEAR, ST_DOWN);
        BigDecimal ceBull = composite(ce, RSI_BULL, ST_UP);
        assertThat(ceBull).as(ce + " (CE twin) chart composite HIGH on a bullish bar").isEqualByComparingTo("1.0");
        assertThat(ceBear).as(ce + " (CE twin) chart composite LOW on a bearish bar").isEqualByComparingTo("0");
      }
    }
  }

  private static BigDecimal composite(String id, BigDecimal rsi, BigDecimal supertrend)
      throws IOException {
    JsonNode config = StrategyDocuments.parse(yaml(id)).config();
    StrategyDefinition def = StrategyCompiler.compile(config);
    return CompositeScorer.score(def, bank(rsi, supertrend), 0).orElseThrow().composite();
  }

  private static BigDecimal threshold(String id) throws IOException {
    JsonNode config = StrategyDocuments.parse(yaml(id)).config();
    return StrategyCompiler.compile(config).scoring().threshold();
  }

  /** Bank returning raw values for the two scoring participants; the composite normalizes them. */
  private static BarValues bank(BigDecimal rsi14, BigDecimal supertrend) {
    Map<String, BigDecimal> raw = Map.of("rsi14", rsi14, "supertrend", supertrend);
    return new BarValues() {
      @Override
      public BigDecimal valueAt(String alias, int primaryIndex) {
        return raw.get(alias);
      }

      @Override
      public BigDecimal previousValueAt(String alias, int primaryIndex) {
        return null;
      }

      @Override
      public BigDecimal builtin(String name, int primaryIndex) {
        return null;
      }
    };
  }

  private static String yaml(String id) throws IOException {
    try (InputStream in =
        ScalperPeCompositeInversionTest.class.getResourceAsStream(
            "/scalper-strategies/" + id + ".yaml")) {
      assertThat(in).as("classpath resource for " + id).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
