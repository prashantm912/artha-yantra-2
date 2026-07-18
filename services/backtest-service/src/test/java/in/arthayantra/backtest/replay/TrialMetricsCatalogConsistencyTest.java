package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.analytics.BenchmarkMetrics;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Pins the shared trial-metrics catalog ({@code contracts/metrics/trial-metrics-catalog.json}, the
 * §9-candidate-03 source of truth the Python optimizer derives its objective allow-list from) to the
 * metric keys the backtest ACTUALLY emits — so the two can never drift silently. A metric added to
 * {@link MetricsCalculator} or the {@link BenchmarkMetrics} block without a matching catalog row (or
 * a catalog row with no emit site) turns this test red. Parity-neutral: reads what the emitters
 * produce, changes no computation.
 */
class TrialMetricsCatalogConsistencyTest {

  private static final Set<String> VALID_UNITS = Set.of("percent", "ratio", "currency", "count", "bars");
  private static final Set<String> VALID_DIRECTIONS =
      Set.of("higher_is_better", "lower_is_better", "neutral");
  private static final Set<String> VALID_SOURCES = Set.of("core", "benchmark", "fold", "fold_aggregate");

  private static JsonNode catalog() throws Exception {
    // surefire's working dir is the module dir; the catalog lives at the repo root.
    Path p = Path.of("..", "..", "contracts", "metrics", "trial-metrics-catalog.json");
    return new ObjectMapper().readTree(Files.readString(p));
  }

  private static Set<String> catalogKeysForSource(JsonNode catalog, String... sources) {
    Set<String> wanted = Set.of(sources);
    Set<String> keys = new TreeSet<>();
    for (JsonNode row : catalog.path("metrics")) {
      if (wanted.contains(row.path("source").asText())) {
        keys.add(row.path("key").asText());
      }
    }
    return keys;
  }

  /** The keys {@link MetricsCalculator#compute} unconditionally writes into the metrics JSONB. */
  private static Set<String> coreEmittedKeys() {
    MetricsCalculator.Metrics m =
        new MetricsCalculator(new ObjectMapper())
            .compute(
                List.<Trade>of(),
                List.<EquityPoint>of(),
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                "1d",
                "1d",
                0L,
                0L);
    Set<String> keys = new TreeSet<>();
    m.full().fieldNames().forEachRemaining(keys::add);
    return keys;
  }

  /** The benchmark-relative keys {@code BacktestRunner.mergeBenchmark} merges in, one per component. */
  private static Set<String> benchmarkEmittedKeys() {
    Set<String> keys = new TreeSet<>();
    for (RecordComponent rc : BenchmarkMetrics.Result.class.getRecordComponents()) {
      keys.add(rc.getName());
    }
    return keys;
  }

  @Test
  void catalogCoreRowsMatchTheMetricsCalculatorEmit() throws Exception {
    assertThat(catalogKeysForSource(catalog(), "core")).isEqualTo(coreEmittedKeys());
  }

  @Test
  void catalogBenchmarkRowsMatchTheBenchmarkResultRecord() throws Exception {
    assertThat(catalogKeysForSource(catalog(), "benchmark")).isEqualTo(benchmarkEmittedKeys());
  }

  @Test
  void catalogFoldRowsAreTheTwoKnownAggregateKeys() throws Exception {
    // foldsExcluded (BacktestRunner fold-path flag) + oos_fold_mean (the walk-forward stream field)
    // have no reflectable emit site, so they are pinned explicitly here.
    assertThat(catalogKeysForSource(catalog(), "fold", "fold_aggregate"))
        .isEqualTo(new TreeSet<>(List.of("foldsExcluded", "oos_fold_mean")));
  }

  @Test
  void catalogCoversEveryEmittedRankableKeyAndNothingPhantom() throws Exception {
    JsonNode catalog = catalog();
    Set<String> catalogKeys = new HashSet<>();
    for (JsonNode row : catalog.path("metrics")) {
      catalogKeys.add(row.path("key").asText());
    }
    Set<String> emitted = new HashSet<>(coreEmittedKeys());
    emitted.addAll(benchmarkEmittedKeys());
    emitted.add("foldsExcluded");
    emitted.add("oos_fold_mean");
    assertThat(catalogKeys).isEqualTo(emitted);
  }

  @Test
  void everyCatalogRowIsWellFormed() throws Exception {
    List<String> keys = new ArrayList<>();
    for (JsonNode row : catalog().path("metrics")) {
      assertThat(row.path("key").asText()).isNotBlank();
      assertThat(row.path("label").asText()).isNotBlank();
      assertThat(VALID_UNITS).contains(row.path("unit").asText());
      assertThat(VALID_DIRECTIONS).contains(row.path("direction").asText());
      assertThat(VALID_SOURCES).contains(row.path("source").asText());
      keys.add(row.path("key").asText());
    }
    assertThat(keys).doesNotHaveDuplicates();
  }
}
