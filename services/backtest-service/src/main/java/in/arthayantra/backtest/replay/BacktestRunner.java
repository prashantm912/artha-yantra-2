package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.analytics.BenchmarkAnalytics;
import in.arthayantra.backtest.analytics.BenchmarkAnalyzer;
import in.arthayantra.backtest.client.MarketDataClient;
import in.arthayantra.backtest.client.StrategyVersionClient;
import in.arthayantra.backtest.client.StrategyVersionClient.ResolvedVersion;
import in.arthayantra.backtest.dispatch.JobCancelledException;
import in.arthayantra.backtest.dispatch.ReplayStub;
import in.arthayantra.backtest.dispatch.TrialResultPublisher;
import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.regime.BenchmarkSeries;
import in.arthayantra.backtest.regime.RegimeLabel;
import in.arthayantra.backtest.regime.RegimeLabeler;
import in.arthayantra.backtest.regime.RegimePreflight;
import in.arthayantra.backtest.replay.folds.FoldPersistence;
import in.arthayantra.backtest.replay.folds.WalkForwardRunner;
import in.arthayantra.backtest.replay.options.OptionsPremiumReplay;
import in.arthayantra.backtest.replay.options.PremiumProvenance;
import in.arthayantra.backtest.replay.options.PremiumSource;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
  private final OptionsPremiumReplay optionsPremiumReplay;
  private final MetricsCalculator metrics;
  private final RunRepository runs;
  private final TradeRepository trades;
  private final ReplayStub stub;
  private final PremiumProvenance premiumProvenance;
  private final WalkForwardRunner walkForward;
  private final RegimeLabeler regimeLabeler;
  private final RegimePreflight regimePreflight;
  private final BenchmarkAnalyzer benchmarkAnalyzer;
  private final TrialResultPublisher trialResults;
  private final MarketDataClient marketData;

  /** Wires the replay collaborators. */
  public BacktestRunner(
      StrategyVersionClient versions,
      CandleReader candleReader,
      ReplayEngine replayEngine,
      OptionsPremiumReplay optionsPremiumReplay,
      MetricsCalculator metrics,
      RunRepository runs,
      TradeRepository trades,
      ReplayStub stub,
      PremiumProvenance premiumProvenance,
      WalkForwardRunner walkForward,
      RegimeLabeler regimeLabeler,
      RegimePreflight regimePreflight,
      BenchmarkAnalyzer benchmarkAnalyzer,
      TrialResultPublisher trialResults,
      MarketDataClient marketData) {
    this.versions = versions;
    this.candleReader = candleReader;
    this.replayEngine = replayEngine;
    this.optionsPremiumReplay = optionsPremiumReplay;
    this.metrics = metrics;
    this.runs = runs;
    this.trades = trades;
    this.stub = stub;
    this.premiumProvenance = premiumProvenance;
    this.walkForward = walkForward;
    this.regimeLabeler = regimeLabeler;
    this.regimePreflight = regimePreflight;
    this.benchmarkAnalyzer = benchmarkAnalyzer;
    this.trialResults = trialResults;
    this.marketData = marketData;
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
    // §D.6 optimizer loop: a TRIAL applies its sampled paramsOverride onto the pinned config via
    // the closed path grammar (re-validated service-side, §D.12) — transient, never persisted as a
    // version. Without this every trial would replay the identical base config.
    JsonNode overrides = request.path("paramsOverride");
    if (overrides.isObject() && !overrides.isEmpty()) {
      config = TrialOverrides.apply(config, overrides);
    }
    StrategyDefinition definition = StrategyCompiler.compile(config);
    SeriesKey signal = signalInstrument(config, request);
    OffsetDateTime from = OffsetDateTime.parse(dateTime(request.path("from").asText()));
    OffsetDateTime to = OffsetDateTime.parse(dateTime(request.path("to").asText()));
    BigDecimal initialEquity =
        request.has("initialCapital")
            ? request.get("initialCapital").decimalValue()
            : DEFAULT_CAPITAL;
    long seed = request.path("seed").asLong(0);
    // EVO §3.2.5 cost-stress: the request-level slippage multiplier (validated + pinned into the
    // request JSONB at submission), read back here and carried on the CostConfig into every fill.
    // Absent ⇒ 1 ⇒ byte-identical to an unstressed run. One CostConfig instance, reused across the
    // candle replay and the fold path so a stressed run degrades consistently.
    BigDecimal slippageMultiplier = stressSlippageMultiplier(request);
    CostConfig costConfig = CostConfig.defaults().withSlippageMultiplier(slippageMultiplier);

    // Auto-warm every series this run reads (primary 1m + each context (instrument, timeframe) +
    // the benchmark daily) into the shared store via market-data's cache-first GET BEFORE the reads
    // below. Backtest replay never fetches on demand, so without this a fresh/uncovered window reads
    // empty. Best-effort (each call swallows its own failure); submission already warmed the two
    // pre-flight series, this covers the context series too.
    warmSeries(definition, signal, config, from, to);

    List<EngineCandle> primary1m =
        candleReader.read(signal.exchange(), signal.tradingsymbol(), "1m", from, to);
    Map<SeriesKey, List<EngineCandle>> contexts =
        contextSeries(definition, signal, from, to);
    // 2b-E2b: the strike-reference spot the ATM strike anchors on (e.g. SENSEX-fut for SENSEX options).
    // Absent universe.strike_reference -> the signal series itself, so the read is reused and behaviour
    // is byte-identical to before. Only the options path consumes it (the candle path ignores it).
    SeriesKey strikeRef = strikeReferenceInstrument(config, signal);
    List<EngineCandle> strikeRef1m =
        strikeRef.equals(signal)
            ? primary1m
            : candleReader.read(strikeRef.exchange(), strikeRef.tradingsymbol(), "1m", from, to);
    // 2b-E2b: a configured strike_reference with NO coverage would SILENTLY fall back to the signal
    // price (anchoring SENSEX strikes on the NIFTY-future price) -> a wrong strike, not a clear error.
    // Fail loud at DATA_GAP instead (e.g. SENSEX-FUT-CONT not backfilled, or the wrong exchange).
    if (!strikeRef.equals(signal) && strikeRef1m.isEmpty()) {
      throw new ApiException(
          422,
          ErrorCodes.DATA_GAP,
          "strike_reference " + strikeRef.exchange() + ":" + strikeRef.tradingsymbol()
              + " has no candle coverage over the run window",
          Map.of(
              "exchange", strikeRef.exchange(),
              "tradingsymbol", strikeRef.tradingsymbol(),
              "from", from.toString(),
              "to", to.toString()));
    }
    checkpoint(cancelled, job.id(), progress, 40);

    // Part 2: an options_of_underlying strategy is replayed premium-as-primary — signals on the
    // underlying (the signal instrument here), fills/marks/exits on the option's own premium series.
    // Every other strategy stays on the byte-identical candle-close ReplayEngine path (its goldens hold).
    boolean optionsStrategy = premiumProvenance.isOptionsStrategy(config);
    // Audit row 6: an armed OI gate's replay-time Connecting-Dots lookups can silently return EMPTY
    // (MarketDataClient swallows failures) — the gate then degrades to no-filtering. Tally
    // fetched-vs-total per run so a degraded-gate run is distinguishable on the metrics JSONB.
    OptionsPremiumReplay.GateCoverage oiGateCoverage = new OptionsPremiumReplay.GateCoverage();
    ReplayResult result =
        optionsStrategy
            ? optionsPremiumReplay.replay(
                definition,
                config,
                signal.exchange(),
                signal.tradingsymbol(),
                primary1m,
                strikeRef1m,
                contexts,
                initialEquity,
                oiGateCoverage,
                slippageMultiplier)
            : replayEngine.replay(
                definition,
                signal.exchange(),
                signal.tradingsymbol(),
                primary1m,
                contexts,
                initialEquity,
                costConfig,
                true,
                // D17b: smooth intra-replay progress over the 40→80 band so the bar no longer sits at 40
                // for the whole replay. Pure progress side-channel — replay numerics are unchanged.
                pct -> progress.accept(40 + pct * 40 / 100));
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
    if (oiGateCoverage.armed()) {
      m.full().put("oiGateCoverage", oiGateCoverage.label()); // e.g. "42/45" fetched-vs-total
    }
    // EVO §3.2.5: surface the cost-stress multiplier on the run's result metrics (in ADDITION to the
    // job-request JSONB provenance) so the optimizer can label a stressed run's degradation without
    // re-reading the job request. Only when actually stressed → an unstressed run's metrics stay
    // byte-identical (no new key).
    if (slippageMultiplier.compareTo(BigDecimal.ONE) != 0) {
      m.full().put("slippageMultiplier", slippageMultiplier.toPlainString());
    }

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
                strikeRef1m,
                contexts,
                initialEquity,
                costConfig,
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
            candleReader.maxFetchedAt(signal.exchange(), signal.tradingsymbol(), from, to),
            extraSeriesLegs(signal, strikeRef, strikeRef1m, contexts, optionsStrategy,
                result.trades(), from, to));

    UUID runId =
        runs.insert(
            job.id(),
            resolved.versionId(),
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
            request.path("universeChecksum").asText(null),
            engineVersion(resolved),
            premiumSource,
            folds,
            benchmark,
            // Audit T3 / EVO §13 row 4: the run inherits its job's actor — a run exists on behalf of
            // whoever submitted the job (owner backtest, or optimizer:{sweepId} for a TRIAL).
            job.createdBy());
    trades.insertAll(runId, result.trades());

    // §D.7 ask/tell back-half: a TRIAL emits its metrics onto optimizations.results so the
    // optimizer can study.tell() and (Phase 34) feed per-fold OOS objectives to the pruner.
    if (job.kind() == JobKind.TRIAL) {
      trialResults.publish(
          job.id(),
          job.parentJobId(),
          runId,
          m.full(),
          folds == null ? null : folds.oosFoldMean(),
          oosFoldObjectives(folds));
    }

    progress.accept(100);
    log.info("backtest run {} completed: {} trades", runId, result.trades().size());
  }

  /**
   * The additional {@link DataHash.SeriesLeg}s for every series the run consumed beyond the primary
   * 1m (audit row 6, datahash-partial-coverage): the strike-reference series (options path, when
   * decoupled from the signal), each context series, and each traded option contract's premium
   * series (its stored 1m bar count + max fetched_at over the run window). Legs mirror the primary
   * tuple's shape; option contracts are sorted so the tuple order is stable. Empty for a run that
   * consumed ONLY the primary series — its hash stays byte-identical to the pre-extension algorithm.
   */
  private List<DataHash.SeriesLeg> extraSeriesLegs(
      SeriesKey signal,
      SeriesKey strikeRef,
      List<EngineCandle> strikeRef1m,
      Map<SeriesKey, List<EngineCandle>> contexts,
      boolean optionsStrategy,
      List<Trade> trades,
      OffsetDateTime from,
      OffsetDateTime to) {
    List<DataHash.SeriesLeg> legs = new ArrayList<>();
    if (optionsStrategy && !strikeRef.equals(signal)) {
      legs.add(
          new DataHash.SeriesLeg(
              strikeRef.exchange(),
              strikeRef.tradingsymbol(),
              "1m",
              strikeRef1m.size(),
              candleReader.maxFetchedAt(strikeRef.exchange(), strikeRef.tradingsymbol(), from, to)));
    }
    for (Map.Entry<SeriesKey, List<EngineCandle>> e : contexts.entrySet()) {
      SeriesKey k = e.getKey();
      legs.add(
          new DataHash.SeriesLeg(
              k.exchange(),
              k.tradingsymbol(),
              k.interval(),
              e.getValue().size(),
              candleReader.maxFetchedAt(k.exchange(), k.tradingsymbol(), from, to)));
    }
    if (optionsStrategy) {
      // one leg per DISTINCT traded contract (the options-path Trade carries the contract's
      // exchange/tradingsymbol) — a TreeMap so the leg order is stable across replays
      Map<String, Trade> byContract = new TreeMap<>();
      for (Trade t : trades) {
        byContract.putIfAbsent(t.exchange() + "|" + t.tradingsymbol(), t);
      }
      for (Trade t : byContract.values()) {
        legs.add(
            new DataHash.SeriesLeg(
                t.exchange(),
                t.tradingsymbol(),
                "1m",
                candleReader.count1mBuckets(t.exchange(), t.tradingsymbol(), from, to),
                candleReader.maxFetchedAt(t.exchange(), t.tradingsymbol(), from, to)));
      }
    }
    return legs;
  }

  /** The per-fold OOS objective (OOS sharpe) array for the pruner; empty for a full-window trial. */
  private ArrayNode oosFoldObjectives(FoldPersistence folds) {
    ArrayNode out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    if (folds != null && folds.foldMetrics() != null) {
      for (JsonNode fold : folds.foldMetrics()) {
        JsonNode oosSharpe = fold.path("oosMetrics").path("sharpe");
        if (oosSharpe.isValueNode() && !oosSharpe.isNull()) {
          out.add(oosSharpe.asText());
        }
      }
    }
    return out;
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

  /**
   * Best-effort cache-first warm of every series the replay will read: primary 1m, each indicator's
   * (instrument, timeframe) context, and the benchmark daily series with WARMUP_SESSIONS lookback.
   * Each {@link MarketDataClient#warm} swallows its own failure, so a market-data outage never aborts
   * a run — the subsequent {@code candleReader.read} simply sees whatever the store already holds.
   */
  private void warmSeries(
      StrategyDefinition definition,
      SeriesKey signal,
      JsonNode config,
      OffsetDateTime from,
      OffsetDateTime to) {
    marketData.warm(signal.exchange(), signal.tradingsymbol(), "1m", from, to);
    for (StrategyDefinition.IndicatorSpec ind : definition.indicators()) {
      String exch = ind.instrument() == null ? signal.exchange() : ind.instrument().exchange();
      String sym =
          ind.instrument() == null ? signal.tradingsymbol() : ind.instrument().tradingsymbol();
      marketData.warm(exch, sym, ind.timeframe(), from, to);
    }
    try {
      BenchmarkSeries benchmark = BenchmarkSeries.resolve(config);
      marketData.warm(
          benchmark.exchange(),
          benchmark.tradingsymbol(),
          "1d",
          from.minusDays(RegimeLabeler.WARMUP_SESSIONS * 2L),
          to);
    } catch (IllegalArgumentException malformedBenchmark) {
      // a malformed benchmark is a schema concern handled elsewhere — nothing to warm
    }
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

  // Package-visible for BacktestRunnerSignalInstrumentTest.
  static SeriesKey signalInstrument(JsonNode config, JsonNode request) {
    // E1 §3.3 + B9 (EVO §13 row 19): futures_screener and the swing FUNNEL modes (manas_arora_funnel /
    // minervini_funnel) carry NO instrument in the config — their picks are pinned into the request
    // universe array at submission (JobsService). The runner is single-signal-instrument, so v1 signals
    // on the TOP pinned pick. GATED to these dynamic-list modes so every explicit/underlying mode stays
    // byte-identical: a global pinned-array-first would flip futures_of_underlying from the underlying
    // spot to the contract.
    String mode = config.path("universe").path("mode").asText();
    if ("futures_screener".equals(mode)
        || "manas_arora_funnel".equals(mode)
        || "minervini_funnel".equals(mode)) {
      JsonNode pinned = request.path("universe");
      if (pinned.isArray() && !pinned.isEmpty()) {
        JsonNode first = pinned.get(0);
        return new SeriesKey(
            first.path("exchange").asText(), first.path("tradingsymbol").asText(), "1m");
      }
      throw new IllegalArgumentException(
          mode + " backtest needs a pinned universe (submission produced no candidates)");
    }
    JsonNode instruments = config.path("universe").path("instruments");
    if (instruments.isArray() && !instruments.isEmpty()) {
      JsonNode first = instruments.get(0);
      return new SeriesKey(first.path("exchange").asText(), first.path("tradingsymbol").asText(), "1m");
    }
    // 2b-E2: an explicit universe.signal_underlying decouples the SIGNAL series from the
    // options-execution root in universe.underlying (e.g. signal on NIFTY-FUT-CONT, legs on SENSEX).
    // Absent → the signal IS the underlying (below), so all existing configs are unchanged.
    JsonNode signalUnderlying = config.path("universe").path("signal_underlying");
    if (signalUnderlying.isObject()
        && signalUnderlying.hasNonNull("exchange")
        && signalUnderlying.hasNonNull("tradingsymbol")) {
      return new SeriesKey(
          signalUnderlying.get("exchange").asText(),
          signalUnderlying.get("tradingsymbol").asText(),
          "1m");
    }

    // options_of_underlying / futures_of_underlying: the signal instrument IS the underlying spot.
    // schema-v1's universe.underlying is an instrumentRef OBJECT {exchange, tradingsymbol} — resolve it
    // (the premium-as-primary replay then routes fills onto the option's own series). The pre-2026-06-25
    // code only handled a legacy "EXCH:SYMBOL" string, so a schema-valid options strategy threw here.
    JsonNode underlying = config.path("universe").path("underlying");
    if (underlying.isObject()
        && underlying.hasNonNull("exchange")
        && underlying.hasNonNull("tradingsymbol")) {
      return new SeriesKey(
          underlying.get("exchange").asText(), underlying.get("tradingsymbol").asText(), "1m");
    }
    // Legacy colon-string form ("NSE:NIFTY 50").
    String underlyingStr = underlying.asText(null);
    if (underlyingStr != null && underlyingStr.contains(":")) {
      String[] parts = underlyingStr.split(":", 2);
      return new SeriesKey(parts[0], parts[1], "1m");
    }
    throw new IllegalArgumentException("backtest needs an explicit single-instrument universe (v1)");
  }

  /**
   * The strike-reference instrument (2b-E2b): {@code universe.strike_reference} if set, else the signal
   * instrument. Absent the override the ATM strike is anchored on the signal series (byte-identical to
   * the pre-2b-E2b behaviour). Used so a SENSEX-options strategy signalling on NIFTY-FUT-CONT anchors its
   * strike on SENSEX-FUT-CONT, not the NIFTY-future price.
   */
  static SeriesKey strikeReferenceInstrument(JsonNode config, SeriesKey signal) {
    JsonNode ref = config.path("universe").path("strike_reference");
    if (ref.isObject() && ref.hasNonNull("exchange") && ref.hasNonNull("tradingsymbol")) {
      return new SeriesKey(ref.get("exchange").asText(), ref.get("tradingsymbol").asText(), "1m");
    }
    return signal;
  }

  private static String dateTime(String value) {
    // accept a plain date (00:00 IST) or a full offset date-time
    return value.length() == 10 ? value + "T00:00:00+05:30" : value;
  }

  /**
   * The EVO §3.2.5 cost-stress slippage multiplier pinned into the request JSONB at submission
   * ({@code stressOverrides.slippageMultiplier}), already range-validated there. Absent (an unstressed
   * run) ⇒ {@code 1}, which the {@code FillSimulator} treats as byte-identical to no stress.
   */
  private static BigDecimal stressSlippageMultiplier(JsonNode request) {
    JsonNode node = request.path("stressOverrides").path("slippageMultiplier");
    return node.isNumber() ? node.decimalValue() : BigDecimal.ONE;
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
