package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.analytics.BenchmarkAnalytics;
import in.arthayantra.backtest.analytics.BenchmarkAnalyzer;
import in.arthayantra.backtest.client.StrategyVersionClient;
import in.arthayantra.backtest.client.StrategyVersionClient.ResolvedVersion;
import in.arthayantra.backtest.dispatch.JobCancelledException;
import in.arthayantra.backtest.dispatch.ReplayStub;
import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.backtest.regime.BenchmarkSeries;
import in.arthayantra.backtest.regime.RegimeLabel;
import in.arthayantra.backtest.regime.RegimeLabeler;
import in.arthayantra.backtest.regime.RegimePreflight;
import in.arthayantra.backtest.replay.folds.FoldPersistence;
import in.arthayantra.backtest.replay.folds.WalkForwardRunner;
import in.arthayantra.backtest.replay.options.PremiumProvenance;
import in.arthayantra.backtest.replay.options.PremiumSource;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes one BACKTEST / TRIAL job: resolve + compile the pinned strategy version, read candles
 * read-only from {@code marketdata}, replay through the shared engine JAR + {@code FillSimulator},
 * persist the run + trades and the metric catalog. An empty/no-op config falls back to the Phase-28
 * progress stub so the spine remains testable without a real strategy.
 */
@Component
public class BacktestRunner {

  private static final Logger log = LoggerFactory.getLogger(BacktestRunner.class);
  private static final BigDecimal DEFAULT_CAPITAL = new BigDecimal("1000000");

  private final StrategyVersionClient versions;
  private final CandleReader candleReader;
  private final ReplayEngine replayEngine;
  private final MetricsCalculator metrics;
  private final RunRepository runs;
  private final TradeRepository trades;
  private final ReplayStub stub;
  private final PremiumProvenance premiumProvenance;
  private final WalkForwardRunner walkForward;
  private final RegimeLabeler regimeLabeler;
  private final RegimePreflight regimePreflight;
  private final BenchmarkAnalyzer benchmarkAnalyzer;

  /** Wires the replay collaborators. */
  public BacktestRunner(
      StrategyVersionClient versions,
      CandleReader candleReader,
      ReplayEngine replayEngine,
      MetricsCalculator metrics,
      RunRepository runs,
      TradeRepository trades,
      ReplayStub stub,
      PremiumProvenance premiumProvenance,
      WalkForwardRunner walkForward,
      RegimeLabeler regimeLabeler,
      RegimePreflight regimePreflight,
      BenchmarkAnalyzer benchmarkAnalyzer) {
    this.versions = versions;
    this.candleReader = candleReader;
    this.replayEngine = replayEngine;
    this.metrics = metrics;
    this.runs = runs;
    this.trades = trades;
    this.stub = stub;
    this.premiumProvenance = premiumProvenance;
    this.walkForward = walkForward;
    this.regimeLabeler = regimeLabeler;
    this.regimePreflight = regimePreflight;
    this.benchmarkAnalyzer = benchmarkAnalyzer;
  }

  /** Runs the job; throws {@link JobCancelledException} on cancel and other exceptions on failure. */
  public void run(Job job, IntConsumer progress, BooleanSupplier cancelled) {
    JsonNode request = job.request();
    String strategyId = request.path("strategyId").asText(null);
    String version = request.path("strategyVersion").asText(null);
    ResolvedVersion resolved = versions.resolve(strategyId, version);
    JsonNode config = resolved == null ? null : resolved.config();

    if (config == null || config.isMissingNode() || !config.has("indicators") || !config.has("universe")) {
      stub.run(job.id(), progress, cancelled); // Phase-28 spine path (no real strategy)
      return;
    }

    checkpoint(cancelled, job.id(), progress, 10);
    StrategyDefinition definition = StrategyCompiler.compile(config);
    SeriesKey signal = signalInstrument(config);
    OffsetDateTime from = OffsetDateTime.parse(dateTime(request.path("from").asText()));
    OffsetDateTime to = OffsetDateTime.parse(dateTime(request.path("to").asText()));
    BigDecimal initialEquity =
        request.has("initialCapital")
            ? request.get("initialCapital").decimalValue()
            : DEFAULT_CAPITAL;
    long seed = request.path("seed").asLong(0);

    List<EngineCandle> primary1m =
        candleReader.read(signal.exchange(), signal.tradingsymbol(), "1m", from, to);
    Map<SeriesKey, List<EngineCandle>> contexts =
        contextSeries(definition, signal, from, to);
    checkpoint(cancelled, job.id(), progress, 40);

    ReplayResult result =
        replayEngine.replay(
            definition,
            signal.exchange(),
            signal.tradingsymbol(),
            primary1m,
            contexts,
            initialEquity,
            CostConfig.defaults(),
            true);
    checkpoint(cancelled, job.id(), progress, 80);

    MetricsCalculator.Metrics m =
        metrics.compute(
            result.trades(),
            result.equityCurve(),
            result.initialEquity(),
            result.finalEquity(),
            definition.primaryTimeframe(),
            result.totalBars(),
            result.barsInPosition());
    m.full().put("strategyChecksum", resolved.checksum());

    // §D.4 fold-mode trigger (Phase 31): a plain /backtests/run job is FULL-WINDOW (folds == null,
    // the four fold columns stay NULL). Fold-mode activates ONLY when the future optimizer/stress
    // path sets request.foldContext == true. With a walk_forward config -> rolling/anchored folds
    // (guard 2); without one but still fold-context -> the implicit 70/30 split (guard 1). The
    // run-level headline Metrics/equity curve stay full-window (a meaningful /results payload); the
    // OOS objective the optimizer ranks on lands in oos_fold_mean/oos_fold_std + sharpe_degradation.
    boolean foldContext = request.path("foldContext").asBoolean(false);
    FoldPersistence folds =
        foldContext
            ? walkForward.run(
                config,
                definition,
                signal.exchange(),
                signal.tradingsymbol(),
                primary1m,
                contexts,
                initialEquity,
                CostConfig.defaults(),
                true,
                from,
                to,
                regimeLabels(config, from, to))
            : null;

    // §D.4 guard 3/6: surface the min_trades exclusion count as a run-level flag on the metrics
    // JSONB ("foldsExcluded"), never a silent drop — a consumer of /results or /folds can then tell
    // "3 valid, 0 excluded" from "3 valid, 2 excluded under min_trades". Only on the fold path; a
    // plain full-window run carries no such key.
    if (folds != null) {
      m.full().put("foldsExcluded", folds.excludedFoldCount());
    }

    // §D.16 benchmark-relative block: pure post-processing over the persisted equity curve + the
    // benchmark daily series (guard-6's series). Absent coverage -> NULL columns + an explicit
    // "benchmarkCoverage":"absent" flag on the metrics JSONB, never silently zero.
    BenchmarkAnalytics benchmark =
        benchmarkAnalyzer.analyze(
            config, result.equityCurve(), from, to, result.initialEquity(), cagrFraction(m));
    mergeBenchmark(m.full(), benchmark);

    // §D.15 premium provenance: resolved BEFORE results persist, never defaulted/null.
    PremiumSource premiumSource = premiumProvenance.forCandleReplay(config);

    String dataHash =
        DataHash.of(
            signal.exchange(),
            signal.tradingsymbol(),
            "1m",
            from,
            to,
            primary1m.size(),
            candleReader.maxFetchedAt(signal.exchange(), signal.tradingsymbol(), from, to));

    UUID runId =
        runs.insert(
            job.id(),
            resolved.strategyId(),
            signal.exchange(),
            signal.tradingsymbol(),
            definition.primaryTimeframe(),
            from,
            to,
            result,
            m,
            request.has("paramsOverride") ? request.get("paramsOverride") : null,
            seed,
            dataHash,
            engineVersion(resolved),
            premiumSource,
            folds,
            benchmark);
    trades.insertAll(runId, result.trades());
    progress.accept(100);
    log.info("backtest run {} completed: {} trades", runId, result.trades().size());
  }

  /**
   * Resolves the benchmark + computes the T−1 regime labels over the run window for fold attribution
   * (guard 6, §D.4). The benchmark warm-up coverage is re-checked here (the same gate {@code
   * JobsService.submit} runs at submission) so a fold run never attributes against partial coverage.
   */
  private Map<LocalDate, RegimeLabel> regimeLabels(
      JsonNode config, OffsetDateTime from, OffsetDateTime to) {
    BenchmarkSeries benchmark = BenchmarkSeries.resolve(config);
    regimePreflight.check(benchmark, from);
    List<EngineCandle> dailyBenchmark =
        candleReader.readDailyWithWarmup(
            benchmark.exchange(),
            benchmark.tradingsymbol(),
            from,
            to,
            RegimeLabeler.WARMUP_SESSIONS);
    return regimeLabeler.label(dailyBenchmark);
  }

  /** The strategy CAGR as a fraction (the metrics JSONB stores it as a percent). */
  private static BigDecimal cagrFraction(MetricsCalculator.Metrics m) {
    JsonNode cagr = m.full().path("cagr");
    if (cagr.isMissingNode() || cagr.isNull()) {
      return null;
    }
    return new BigDecimal(cagr.asText()).movePointLeft(2);
  }

  /**
   * Folds the §D.16 benchmark block into the metrics JSONB: the relative metrics (as the §D.9
   * string convention), the payload-only up/down capture, and the price-index caveat — or, when the
   * benchmark was not covered, an explicit {@code "benchmarkCoverage":"absent"} flag so a consumer
   * never mistakes a missing benchmark for a zero one.
   */
  private static void mergeBenchmark(ObjectNode full, BenchmarkAnalytics benchmark) {
    if (!benchmark.present()) {
      full.put("benchmarkCoverage", "absent");
      appendCaveat(full, "Benchmark coverage absent; benchmark-relative metrics omitted.");
      return;
    }
    full.put("benchmarkCoverage", "present");
    putDecimal(full, "alpha", benchmark.alpha());
    putDecimal(full, "beta", benchmark.beta());
    putDecimal(full, "informationRatio", benchmark.informationRatio());
    putDecimal(full, "excessCagr", benchmark.excessCagr());
    putDecimal(full, "upCapture", benchmark.upCapture());
    putDecimal(full, "downCapture", benchmark.downCapture());
    appendCaveat(full, BenchmarkAnalyzer.priceIndexCaveat());
  }

  private static void putDecimal(ObjectNode node, String key, BigDecimal value) {
    if (value == null) {
      node.putNull(key);
    } else {
      node.put(key, value.toPlainString());
    }
  }

  private static void appendCaveat(ObjectNode full, String caveat) {
    ArrayNode caveats =
        full.has("caveats") && full.get("caveats").isArray()
            ? (ArrayNode) full.get("caveats")
            : full.putArray("caveats");
    caveats.add(caveat);
  }

  private Map<SeriesKey, List<EngineCandle>> contextSeries(
      StrategyDefinition definition, SeriesKey signal, OffsetDateTime from, OffsetDateTime to) {
    Map<SeriesKey, List<EngineCandle>> contexts = new LinkedHashMap<>();
    for (StrategyDefinition.IndicatorSpec ind : definition.indicators()) {
      String exch = ind.instrument() == null ? signal.exchange() : ind.instrument().exchange();
      String sym = ind.instrument() == null ? signal.tradingsymbol() : ind.instrument().tradingsymbol();
      String tf = ind.timeframe();
      boolean onSignalPrimary =
          exch.equals(signal.exchange())
              && sym.equals(signal.tradingsymbol())
              && (tf.equals(definition.primaryTimeframe()) || tf.equals("1m"));
      if (onSignalPrimary) {
        continue;
      }
      SeriesKey key = new SeriesKey(exch, sym, tf);
      contexts.computeIfAbsent(key, k -> candleReader.read(exch, sym, tf, from, to));
    }
    return contexts;
  }

  private static SeriesKey signalInstrument(JsonNode config) {
    JsonNode instruments = config.path("universe").path("instruments");
    if (instruments.isArray() && !instruments.isEmpty()) {
      JsonNode first = instruments.get(0);
      return new SeriesKey(first.path("exchange").asText(), first.path("tradingsymbol").asText(), "1m");
    }
    String underlying = config.path("universe").path("underlying").asText(null);
    if (underlying != null && underlying.contains(":")) {
      String[] parts = underlying.split(":", 2);
      return new SeriesKey(parts[0], parts[1], "1m");
    }
    throw new IllegalArgumentException("backtest needs an explicit single-instrument universe (v1)");
  }

  private static String dateTime(String value) {
    // accept a plain date (00:00 IST) or a full offset date-time
    return value.length() == 10 ? value + "T00:00:00+05:30" : value;
  }

  private static String engineVersion(ResolvedVersion resolved) {
    return "strategy-engine/" + (resolved.version() == null ? "v1" : resolved.version());
  }

  private static void checkpoint(
      BooleanSupplier cancelled, UUID jobId, IntConsumer progress, int pct) {
    if (cancelled.getAsBoolean()) {
      throw new JobCancelledException(jobId);
    }
    progress.accept(pct);
  }
}
