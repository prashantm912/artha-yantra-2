package in.arthayantra.strategyengine;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.indicators.EngineIndicator;
import in.arthayantra.strategyengine.indicators.IndicatorRegistry;
import in.arthayantra.strategyengine.series.EngineSeries;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Phase 19 acceptance: every registry indicator matches its committed reference vector EXACTLY
 * (decimal-string compare at the 8-dp engine boundary; blank rows assert null/warm-up).
 * SUPERTREND is behavior-tested separately (ta4j-internal band ratchet — see the generator
 * header) and frozen end-to-end by the Phase 23 goldens.
 */
class IndicatorVectorTest {

  private record Vector(String csv, String indicator, Map<String, Object> params, boolean context) {}

  private static final Vector[] VECTORS = {
    new Vector("EMA_period9", "EMA", Map.of("period", 9), false),
    new Vector("SMA_period5", "SMA", Map.of("period", 5), false),
    new Vector("RSI_period14", "RSI", Map.of("period", 14), false),
    new Vector("ATR_period14", "ATR", Map.of("period", 14), false),
    new Vector("ADX_period14", "ADX", Map.of("period", 14), false),
    new Vector("MACD_HIST_3_9_3", "MACD_HIST", Map.of("fast", 3, "slow", 9, "signal", 3), false),
    new Vector("VWAP_session", "VWAP", Map.of(), false),
    new Vector("VOLUME_RATIO_lookback20", "VOLUME_RATIO", Map.of("lookback", 20), false),
    new Vector("OI_CHANGE_PCT_lookback6", "OI_CHANGE_PCT", Map.of("lookback", 6), false),
    new Vector("ORB_HIGH_window15", "ORB_HIGH", Map.of("window_minutes", 15), false),
    new Vector("ORB_LOW_window15", "ORB_LOW", Map.of("window_minutes", 15), false),
    new Vector("PREV_DAY_HIGH", "PREV_DAY_HIGH", Map.of(), false),
    new Vector("PREV_DAY_LOW", "PREV_DAY_LOW", Map.of(), false),
    new Vector("PREV_DAY_CLOSE", "PREV_DAY_CLOSE", Map.of(), false),
    new Vector("DAY_HIGH", "DAY_HIGH", Map.of(), false),
    new Vector("DAY_LOW", "DAY_LOW", Map.of(), false),
    new Vector("GAP_PCT", "GAP_PCT", Map.of(), false),
    new Vector("RS_VS_INDEX_lookback5", "RS_VS_INDEX", Map.of("lookback", 5), true),
    new Vector("VIX_LEVEL", "VIX_LEVEL", Map.of(), true),
    new Vector("VWMA_period20", "VWMA", Map.of("period", 20), false),
    new Vector("BASIS_PCT", "BASIS_PCT", Map.of(), true),
    new Vector("ADVANCE_DECLINE_RATIO", "ADVANCE_DECLINE_RATIO", Map.of(), true),
    new Vector("VCP_PIVOT", "VCP_PIVOT", Map.of(), true),
  };

  @TestFactory
  Stream<DynamicTest> indicatorsMatchCommittedVectorsExactly() {
    EngineSeries primary = VectorFixtures.primarySeries();
    EngineSeries context = VectorFixtures.contextSeries();
    return Stream.of(VECTORS)
        .map(
            vector ->
                DynamicTest.dynamicTest(
                    vector.csv(),
                    () -> {
                      EngineIndicator indicator =
                          IndicatorRegistry.create(
                              vector.indicator(),
                              primary,
                              vector.context() ? context : null,
                              vector.params());
                      assertMatches(vector.csv(), indicator);
                    }));
  }

  private static void assertMatches(String csv, EngineIndicator indicator) throws Exception {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                IndicatorVectorTest.class.getResourceAsStream("/vectors/" + csv + ".csv"),
                StandardCharsets.UTF_8))) {
      String header = reader.readLine();
      assertThat(header).isEqualTo("index,expected");
      String line;
      while ((line = reader.readLine()) != null) {
        int comma = line.indexOf(',');
        int index = Integer.parseInt(line.substring(0, comma));
        String expected = line.substring(comma + 1);
        BigDecimal actual = indicator.valueAt(index);
        if (expected.isEmpty()) {
          assertThat(actual)
              .as("%s[%d] must be null (warm-up / unavailable)", csv, index)
              .isNull();
        } else {
          assertThat(actual).as("%s[%d] must be computed", csv, index).isNotNull();
          assertThat(actual.setScale(8, RoundingMode.HALF_UP).toPlainString())
              .as("%s[%d]", csv, index)
              .isEqualTo(expected);
        }
      }
    }
  }
}
