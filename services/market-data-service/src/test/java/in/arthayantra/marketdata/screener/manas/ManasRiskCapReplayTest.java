package in.arthayantra.marketdata.screener.manas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import in.arthayantra.marketdata.screener.manas.ManasAroraSwingBacktest.BtTrade;
import in.arthayantra.marketdata.screener.manas.ManasAroraSwingBacktest.Variant;
import in.arthayantra.marketdata.screener.minervini.geometry.DailyBar;
import in.arthayantra.marketdata.screener.minervini.geometry.VcpDetector;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.PositionSizer;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * E4/M40 fresh-entry aggregate-risk-cap measurement — REBUILT 2026-08-02 after cross-vendor review
 * (round 2) found the first version measured a 6-SLOT SURROGATE, not the real 6% risk ratio, and used
 * an admission model that was not LIVE-equivalent (independent per-setup books; wrong entry/exit pass
 * order). See {@code docs/signal-analysis/2026-08-02-m40-risk-cap-backtest.md} for the full receipt.
 *
 * <p><b>Opt-in via a system property, not deleted and not requiring a source edit</b> (per the review's
 * instruction: "if there is no natural home for a measurement harness, propose one rather than
 * deleting it" — and a manual-run artifact needing source edits to run is only half auditable). This
 * class requires a live Postgres connection that does not exist in CI's ephemeral Testcontainers
 * environment, so by DEFAULT (the system property unset) {@code @EnabledIfSystemProperty} reports it
 * SKIPPED, never FAILED, while it still compiles (hence type-checked and diff-reviewable) on every
 * build. To re-run, no source edit needed:
 * {@code ./mvnw.cmd -pl services/market-data-service -am test -Dtest=ManasRiskCapReplayTest
 * -Dmanas.replay.enabled=true} — optionally override {@code -Dmanas.replay.pgHost}, {@code
 * -Dmanas.replay.pgPasswordFile}, {@code -Dmanas.replay.outputFile} (see {@link #replay()} for the
 * exact property names and defaults).
 *
 * <h2>What this fixes vs. the withdrawn v1</h2>
 *
 * <ul>
 *   <li><b>Real risk ratio, not a slot count (Critical 1).</b> Quantity is sized against MTM equity AT
 *       ENTRY ({@link PositionSizer#size}, mirroring {@code PaperEmissionGuard.suggestedQty}); the cap
 *       divides accumulated rupee risk by CURRENT MTM equity (mirroring {@code PaperAccountService
 *       .equity} = startingCapital + realized + unrealized-marked-to-today's-close, and {@code
 *       ManasPyramidPolicy.breachesRiskCap}). Both are recomputed every session for every open lot —
 *       not inferred from how many positions happen to be open.
 *   <li><b>LIVE-equivalent admission (Critical 2).</b> One position book PER SYMBOL, shared across both
 *       setups (breakout/vcp) — mirroring {@code SwingBatchEngine}'s {@code openLotsBySymbol} + {@code
 *       pyramid.hasRoom} (pyramiding off ⇒ ANY open lot for a symbol blocks BOTH setups). Same-day
 *       double-fire ties resolve to breakout (a documented, not production-derived, convention — see
 *       Open Doubts). Each session runs its ENTIRE entry pass before any exit pass (mirroring {@code
 *       SwingBatchEngine.runDaily}'s {@code entryPass} then {@code exitPass}), so a position exiting
 *       today still occupies book capacity/risk during today's entry evaluation.
 *   <li><b>Trade-IDENTITY validation, not aggregate counts (Major 3).</b> {@link #productionFidelity}
 *       replays each symbol/setup standalone using this file's OWN duplicated signal logic + the
 *       production, UNMODIFIED, package-visible {@code ManasAroraSwingBacktest.initialStop}/{@code
 *       positionExit}/{@code stopBreached}, and asserts the resulting (entryDate, exitDate, entryPrice,
 *       exitPrice, exitReason) tuples match production's OWN {@code simulate()} output — for EVERY
 *       trade of EVERY symbol, both setups — not merely equal counts.
 * </ul>
 *
 * <h2>What is reused UNMODIFIED from production (zero duplication)</h2>
 *
 * <p>{@link VcpDetector#detect}, {@link ConsolidationBreakout#detect}, {@link
 * ManasGates#gates}/{@link ManasGates#passed}/{@link ManasGates#withinHigh} (package-visible), {@link
 * ManasAroraSwingBacktest#initialStop}/{@link ManasAroraSwingBacktest#positionExit}/{@link
 * ManasAroraSwingBacktest#stopBreached} (package-visible since #1215's M7 characterization-fixture
 * precedent — this file follows the SAME pattern), {@link ManasAroraBacktestService#percentile}
 * (package-visible), {@link PositionSizer#size} (public, {@code strategy-engine} — already a
 * market-data-service dependency), and {@link in.arthayantra.marketdata.screener.SwingFillPrice}
 * (public; this variant fills {@code at_close}).
 *
 * <h2>What is duplicated (small, pure, individually validated)</h2>
 *
 * <p>The rolling-indicator primitives ({@code sma}/{@code atr}/{@code rollingMax}/{@code
 * rollingMin}/{@code volumeRatio}/{@code avgTurnover}), the entry-signal orchestration ({@code
 * entryFires}/{@code selectionGates}/{@code crossover} — thin wrappers that mostly delegate to the
 * REUSED {@code ManasGates}), the RS cross-sectional pass ({@code weightedRs} + weekly rank dates +
 * per-symbol distribution, mirroring {@code ManasAroraBacktestService.computeAll} Pass 1), the
 * Chandelier trail state-update loop (4 lines, mirroring {@code simulateSetup}'s inline logic — not a
 * separately callable production method), and {@code ManasPyramidPolicy.breachesRiskCap}'s formula (a
 * different Maven module — strategy-signal-service — market-data-service cannot import it; the
 * 6-line pure BigDecimal formula is reproduced here verbatim). All private methods below carry a
 * "mirrors X:Y-Z" citation to the exact production lines they reproduce.
 */
@EnabledIfSystemProperty(
    named = "manas.replay.enabled",
    matches = "true",
    disabledReason =
        "Manual-run measurement harness — requires live Postgres, not present in CI. Opt in with "
            + "-Dmanas.replay.enabled=true. See docs/signal-analysis/2026-08-02-m40-risk-cap-backtest.md"
            + " for the last recorded run.")
class ManasRiskCapReplayTest {

  // ---- doctrine / live-default constants (mirrors ManasAroraSwingBacktest's private statics + the
  // manas-arora-breakout.yaml / manas-arora-vcp.yaml / V021__paper_books.sql live values) -----------
  private static final double ATR_MULT = 2.0; // ManasAroraSwingBacktest.ATR_MULT
  private static final int ATR_PERIOD = 20;
  private static final double TRAIL_ARM_PCT = 0.09; // ManasAroraSwingBacktest.TRAIL_ARM_PCT
  private static final int PARABOLIC_MA = 10;
  private static final double RS_MIN = 70.0; // manas-arora-backtest.rs-min default
  private static final double TURNOVER_FLOOR = 3_750_000.0; // manas-arora-backtest.min-turnover default
  private static final double VOL_MIN = 1.2; // §4.7 expanding-volume gate
  private static final int MIN_BARS = 260; // ManasAroraSwingBacktest.MIN_BARS
  private static final int WEEKLY = 5; // geometry recompute cadence
  private static final int GEO_LOOKBACK = 400;
  private static final int TURNOVER_LOOKBACK = 20;
  private static final int RANK_CADENCE = 5; // ManasAroraBacktestService.RANK_CADENCE
  private static final int RS_LOOKBACK = 252; // ManasAroraBacktestService.RS_LOOKBACK
  private static final int MIN_DIST = 20; // ManasAroraBacktestService.MIN_DIST

  // ---- the book being replayed: manas-arora, V021__paper_books.sql -----------------------------
  private static final double STARTING_CAPITAL = 150_000.0; // V021: ('manas-arora', ..., 150000.00)
  private static final BigDecimal RISK_PCT_EQUITY = new BigDecimal("0.8"); // both YAMLs' risk_pct_equity
  // Was 1.0 until 2026-08-13: at 1.0 the 6.0% AGGREGATE_CAP_PCT saturated at 6 positions and MAX_OPEN=7
  // was unreachable by construction. Keep in lockstep with the two manas YAMLs or this harness replays
  // a sizing the live book no longer uses.
  private static final int MAX_OPEN = 7; // V021 max_open_paper_positions
  private static final double AGGREGATE_CAP_PCT = 6.0; // doctrine §2.2, the candidate arm's new rail
  /**
   * M40 re-measurement (2026-08-02): the equity-BUY slippage {@code LtpSlippageV1} adds to the
   * reference price. PR #1221's authoritative cap check runs against that FILL, not the candle close
   * v3 projected against.
   */
  private static final double ENTRY_SLIPPAGE_BPS = 5.0;

  /**
   * All external inputs are system properties with defaults matching this repo's own dev-stack
   * layout — override rather than edit source. {@code -Dmanas.replay.pgHost} (default
   * {@code 127.0.0.1:5432}), {@code -Dmanas.replay.pgPasswordFile} (default {@code
   * ../../deploy/secrets/postgres_password} — RELATIVE TO THIS MODULE, since Surefire's working
   * directory defaults to {@code ${project.basedir}} = {@code services/market-data-service}, not the
   * repo root), {@code -Dmanas.replay.outputFile} (default a fixed path under the OS temp directory).
   */
  @Test
  void replay() throws Exception {
    String pgHost = System.getProperty("manas.replay.pgHost", "127.0.0.1:5432");
    Path pgPasswordFile =
        Path.of(
            System.getProperty(
                "manas.replay.pgPasswordFile", "../../deploy/secrets/postgres_password"));
    String pw = Files.readString(pgPasswordFile, StandardCharsets.UTF_8).trim();
    SimpleDriverDataSource ds = new SimpleDriverDataSource();
    ds.setDriverClass(org.postgresql.Driver.class);
    ds.setUrl("jdbc:postgresql://" + pgHost + "/artha?currentSchema=marketdata");
    ds.setUsername("artha");
    ds.setPassword(pw);
    JdbcTemplate jdbc = new JdbcTemplate(ds);

    LocalDate from = LocalDate.now().minusYears(11);
    LocalDate warmStart = from.minusDays(600);
    VcpDetector vcpDetector = new VcpDetector(2.5, 2, 6, 0.2, 0.9, 0.5, 0.5, 100, 40, 60, 0, 65);
    ConsolidationBreakout breakoutDetector = new ConsolidationBreakout(2.5, 10, 40, 25);
    ManasAroraSwingBacktest sim = new ManasAroraSwingBacktest(); // all-defaults == live doctrine

    StringBuilder out = new StringBuilder();
    out.append("=== M40 risk-cap replay (v2, LIVE-equivalent) ===\n");
    out.append("from=").append(from).append("\n");

    List<String> symbols = eqSymbols(jdbc);
    out.append("symbolsScanned=").append(symbols.size()).append("\n");

    // ---- Pass 1: RS cross-section (mirrors ManasAroraBacktestService.computeAll Pass 1) ----------
    LocalDate[] rankDates = weeklyRankDates(jdbc, warmStart);
    Map<LocalDate, double[]> dist = rsDistribution(jdbc, symbols, warmStart, rankDates);

    // ---- Pass 2: per-symbol precompute (indicators, geometry via PRODUCTION detectors, RS-rank,
    // pure signal predicates) ----------------------------------------------------------------------
    Map<String, SymbolData> data = new LinkedHashMap<>();
    Map<String, List<DailyBar>> bars = readSeriesBatched(jdbc, symbols, warmStart);
    for (String symbol : symbols) {
      List<DailyBar> b = bars.get(symbol);
      if (b == null || b.size() < MIN_BARS) {
        continue;
      }
      data.put(symbol, precompute(symbol, b, vcpDetector, breakoutDetector, rankDates, dist));
    }
    out.append("symbolsWithSufficientHistory=").append(data.size()).append("\n");

    // ---- Validation: exact trade-identity match against production's OWN simulate() ---------------
    FidelityResult fidelity = productionFidelity(data, sim, from, vcpDetector, breakoutDetector);
    out.append("\n--- fidelity validation (trade-identity, not counts) ---\n");
    out.append("breakout: ").append(fidelity.breakoutChecked).append(" symbols checked, ")
        .append(fidelity.breakoutMatched).append(" trade-for-trade matches, ")
        .append(fidelity.breakoutMismatches.size()).append(" mismatches\n");
    out.append("vcp:      ").append(fidelity.vcpChecked).append(" symbols checked, ")
        .append(fidelity.vcpMatched).append(" trade-for-trade matches, ")
        .append(fidelity.vcpMismatches.size()).append(" mismatches\n");
    if (!fidelity.breakoutMismatches.isEmpty() || !fidelity.vcpMismatches.isEmpty()) {
      out.append("MISMATCHES (first 20):\n");
      List<String> all = new ArrayList<>(fidelity.breakoutMismatches);
      all.addAll(fidelity.vcpMismatches);
      for (String m : all.subList(0, Math.min(20, all.size()))) {
        out.append("  ").append(m).append("\n");
      }
    }

    // ---- Main replay: two independent arms (baseline = MAX_OPEN=7 only; candidate = + the real
    // 6% aggregate open-risk cap), one symbol-shared book each, entries-then-exits pass ordering ----
    LocalDate[] allDates = allTradingDates(jdbc, from);
    java.util.Set<String> noExempt = java.util.Set.of();
    ArmSpec baselineSpec =
        new ArmSpec("baseline (MAX_OPEN=7 only)", false, StopBasis.TRAILED, 0, 0.0, noExempt);
    // v3's candidate, reproduced EXACTLY (trailed basis, close-priced) — the harness-validation gate:
    // if this no longer prints #1218's figures, the harness changed under us and nothing else here is
    // trustworthy.
    ArmSpec v3Spec =
        new ArmSpec("v3 candidate (trailed basis, close-priced)", true, StopBasis.TRAILED, 0, 0.0, noExempt);
    // What PR #1221 actually ships, at the realistic end: ManasGoverningStopCache is empty at the entry
    // pass of the first nightly batch after any strategy-signal-service restart, and the fallback is the
    // persisted, never-ratcheted stop_loss. Plus the fill-priced projection.
    ArmSpec shippedColdSpec =
        new ArmSpec("SHIPPED, cache cold (initial-stop basis, fill-priced)", true, StopBasis.INITIAL,
            0, ENTRY_SLIPPAGE_BPS, noExempt);
    // The other bracket: cache warm on every session (no restart ever) — the ceiling #1221 can reach.
    ArmSpec shippedWarmSpec =
        new ArmSpec("SHIPPED, cache warm (trailed basis, fill-priced)", true, StopBasis.TRAILED,
            0, ENTRY_SLIPPAGE_BPS, noExempt);

    ArmResult baseline = runArm(data, allDates, sim, baselineSpec, from);
    ArmResult v3Candidate = runArm(data, allDates, sim, v3Spec, from);
    ArmResult shippedCold = runArm(data, allDates, sim, shippedColdSpec, from);
    ArmResult shippedWarm = runArm(data, allDates, sim, shippedWarmSpec, from);
    ArmResult candidate = v3Candidate; // the v3 reporting blocks below keep their original meaning

    // Cross-vendor review round 2 (Critical): "2,491/2,491 symbols pass identity" (the fidelity check
    // above) says NOTHING about whether the portfolio replay's own pointer-advance loop actually reaches
    // all of them — that was exactly how the pointer-initialization bug went undetected. Compute the
    // INDEPENDENTLY expected participating count (every symbol whose LAST bar is on/after `from` has at
    // least one usable session; a symbol whose entire series ends before `from` legitimately cannot
    // participate) and assert both arms match it exactly, not merely "most" of them.
    int expectedZeroParticipation = 0;
    for (SymbolData sd : data.values()) {
      if (sd.date()[sd.date().length - 1].isBefore(from)) {
        expectedZeroParticipation++;
      }
    }
    int expectedParticipating = data.size() - expectedZeroParticipation;
    out.append("\n--- portfolio-replay symbol-coverage check (independent of the fidelity check) ---\n");
    out.append("expectedParticipating=").append(expectedParticipating)
        .append(" (of ").append(data.size()).append("; ").append(expectedZeroParticipation)
        .append(" symbols have zero bars on/after `from`)\n");
    out.append("baseline.participatingSymbols=").append(baseline.participatingSymbols.size()).append("\n");
    out.append("candidate.participatingSymbols=").append(candidate.participatingSymbols.size()).append("\n");
    assertEquals(
        expectedParticipating, baseline.participatingSymbols.size(),
        "baseline arm's portfolio-replay symbol coverage must match the independently expected"
            + " population — a mismatch means symbols are being silently excluded from the replay"
            + " (the fidelity check does not exercise this pointer-advance loop and cannot catch this)");
    assertEquals(
        expectedParticipating, candidate.participatingSymbols.size(),
        "candidate arm's portfolio-replay symbol coverage must match the independently expected"
            + " population");
    // M40 re-measurement: the SAME independent coverage assertion for every added arm. A shipped-
    // semantics arm that silently replayed a different population would look exactly like a finding.
    out.append("shippedCold.participatingSymbols=").append(shippedCold.participatingSymbols.size()).append("\n");
    out.append("shippedWarm.participatingSymbols=").append(shippedWarm.participatingSymbols.size()).append("\n");
    assertEquals(
        expectedParticipating, shippedCold.participatingSymbols.size(),
        "shipped-cold arm's symbol coverage must match the expected population");
    assertEquals(
        expectedParticipating, shippedWarm.participatingSymbols.size(),
        "shipped-warm arm's symbol coverage must match the expected population");
    assertEquals(
        baseline.participatingSymbols, shippedCold.participatingSymbols,
        "baseline and shipped-cold must replay the IDENTICAL symbol set, not merely the same count");

    out.append("\n--- baseline (today's live rail: MAX_OPEN=7 only) ---\n");
    out.append(armSummary(baseline));
    out.append("\n--- candidate (baseline + real 6% aggregate open-risk cap) ---\n");
    out.append(armSummary(candidate));
    out.append("\n=== M40 RE-MEASUREMENT: arms under the semantics PR #1221 actually ships ===\n");
    out.append("entrySlippageBps=").append(ENTRY_SLIPPAGE_BPS).append("\n");
    out.append("\n--- ").append(shippedColdSpec.name()).append(" ---\n");
    out.append(armSummary(shippedCold));
    out.append("\n--- ").append(shippedWarmSpec.name()).append(" ---\n");
    out.append(armSummary(shippedWarm));

    // ---- Hybrid sweep: cache cold on 1 session in N (N=1 == always cold; large N -> always warm) ----
    out.append("\n--- hybrid sweep (cache cold on 1 session in N; deploy cadence sets N) ---\n");
    for (int n : new int[] {1, 2, 3, 5, 10, 20}) {
      ArmResult h =
          runArm(
              data, allDates, sim,
              new ArmSpec("hybrid N=" + n, true, StopBasis.HYBRID, n, ENTRY_SLIPPAGE_BPS, noExempt),
              from);
      assertEquals(
          expectedParticipating, h.participatingSymbols.size(),
          "hybrid N=" + n + " arm's symbol coverage must match the expected population");
      out.append("N=").append(n).append(" coldShare=")
          .append(String.format(Locale.ROOT, "%.0f%%", 100.0 / n)).append("  ")
          .append(armSummary(h).replace("\n", " | "));
      out.append("\n");
    }

    // ---- Per-calendar-year return delta (sign-robustness: is one year carrying the whole result?) ---
    out.append("\n--- per-calendar-year return %, baseline vs SHIPPED-cold (sign robustness) ---\n");
    out.append(perYearDelta(baseline, shippedCold));

    // ---- Drop-k: waive the cap for the k biggest marginal-refused winners and re-run -----------------
    List<String> shippedRefused = marginalRefusedKeys(baseline, shippedCold);
    out.append("\n--- drop-k sensitivity (cap waived for the k largest |PnL%| marginal refusals) ---\n");
    Map<String, ClosedOrOpenEntry> baseEntries = baseline.allEntriesByKey();
    List<String> byAbsPnl = new ArrayList<>(shippedRefused);
    byAbsPnl.sort(
        Comparator.comparingDouble(
                (String k) -> {
                  Double p = baseEntries.get(k).pnlPct;
                  return p == null ? 0.0 : Math.abs(p);
                })
            .reversed());
    for (int k : new int[] {1, 3, 5, 10}) {
      if (k > byAbsPnl.size()) {
        continue;
      }
      java.util.Set<String> exempt = new java.util.HashSet<>(byAbsPnl.subList(0, k));
      ArmResult dk =
          runArm(
              data, allDates, sim,
              new ArmSpec("drop-" + k, true, StopBasis.INITIAL, 0, ENTRY_SLIPPAGE_BPS, exempt),
              from);
      out.append("k=").append(k).append(" exempt=").append(exempt).append("  ")
          .append(armSummary(dk).replace("\n", " | ")).append("\n");
    }

    // ---- Marginal-refused set: admitted in baseline, refused ONLY by the aggregate cap in candidate
    Map<String, ClosedOrOpenEntry> baselineEntries = baseline.allEntriesByKey();
    List<String> aggRefusedKeys = new ArrayList<>();
    for (Refusal r : candidate.refusals) {
      if (r.reason.equals("AGG_CAP") && baselineEntries.containsKey(r.key())) {
        aggRefusedKeys.add(r.key());
      }
    }
    out.append("\n--- marginal-refused (admitted in baseline, refused by the 6% cap in candidate) ---\n");
    out.append("count=").append(aggRefusedKeys.size()).append("\n");
    double sumPnl = 0;
    int wins = 0;
    java.util.Set<LocalDate> sessions = new java.util.TreeSet<>();
    for (String key : aggRefusedKeys) {
      ClosedOrOpenEntry e = baselineEntries.get(key);
      sessions.add(e.entryDate);
      if (e.pnlPct != null) {
        sumPnl += e.pnlPct;
        if (e.pnlPct > 0) {
          wins++;
        }
      }
      out.append(
          String.format(
              Locale.ROOT,
              "%s %s entry=%s exit=%s pnlPct=%s exitReason=%s%n",
              e.setup, e.symbol, e.entryDate, e.exitDate,
              e.pnlPct == null ? "OPEN-AT-END" : String.format(Locale.ROOT, "%.2f", e.pnlPct),
              e.exitReason == null ? "-" : e.exitReason));
    }
    int closedCount = (int) aggRefusedKeys.stream().filter(k -> baselineEntries.get(k).pnlPct != null).count();
    out.append("distinctSessions=").append(sessions.size()).append("\n");
    if (closedCount > 0) {
      out.append(
          String.format(
              Locale.ROOT, "avgPnlPct(closed only, n=%d)=%.3f winRate=%.1f%%%n",
              closedCount, sumPnl / closedCount, 100.0 * wins / closedCount));
    }

    // ---- Real aggregate-risk-% distribution (baseline arm, sampled at every entry decision) -------
    out.append("\n--- real aggregate open-risk % distribution (baseline arm, at every entry decision) ---\n");
    out.append(riskDistribution(baseline));

    // ---- M40 re-measurement: the same marginal-refused analysis under SHIPPED semantics ------------
    out.append("\n--- marginal-refused under SHIPPED-cold semantics (full list) ---\n");
    out.append(refusedStats(baseline, shippedRefused, true));
    out.append("\n--- marginal-refused under SHIPPED-warm semantics (summary only) ---\n");
    out.append(refusedStats(baseline, marginalRefusedKeys(baseline, shippedWarm), false));
    out.append("\n--- marginal-refused under v3 semantics, recomputed in-harness (summary only) ---\n");
    out.append(refusedStats(baseline, marginalRefusedKeys(baseline, v3Candidate), false));
    // The risk-% distribution the SHIPPED (cold-cache, initial-stop) basis actually sees. v3's table
    // was sampled on the trailed basis; on the persisted-stop basis every trailed-up winner keeps
    // charging its full initial risk, so this distribution sits materially higher — which is the whole
    // mechanism by which the cap binds more often than v3 modelled.
    ArmResult baselineOnInitialBasis =
        runArm(
            data, allDates, sim,
            new ArmSpec("baseline, risk sampled on the initial-stop basis", false, StopBasis.INITIAL,
                0, ENTRY_SLIPPAGE_BPS, noExempt),
            from);
    out.append("\n--- aggregate open-risk % distribution on the SHIPPED (initial-stop) basis ---\n");
    out.append(riskDistribution(baselineOnInitialBasis));

    System.out.println(out);
    Path outFile =
        Path.of(
            System.getProperty(
                "manas.replay.outputFile",
                System.getProperty("java.io.tmpdir") + "/manas-risk-cap-replay-output.txt"));
    if (outFile.getParent() != null) {
      Files.createDirectories(outFile.getParent());
    }
    try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(outFile, StandardCharsets.UTF_8))) {
      w.print(out);
    }

    assertTrue(fidelity.breakoutMismatches.isEmpty(), "breakout standalone replay must match production exactly");
    assertTrue(fidelity.vcpMismatches.isEmpty(), "vcp standalone replay must match production exactly");
    assertTrue(
        baseline.maxConcurrentOpen <= MAX_OPEN,
        "baseline arm must never exceed MAX_OPEN=" + MAX_OPEN + ", saw " + baseline.maxConcurrentOpen);
  }

  // =================================================================================================
  // Per-symbol precompute
  // =================================================================================================

  /**
   * Everything needed to drive one symbol through the replay, precomputed once. Retains the original
   * {@code bars} + the raw {@code rsRank} array (not just the derived signal booleans) so {@link
   * #productionFidelity} can feed BOTH, unmodified, straight into production's own {@code
   * ManasAroraSwingBacktest.simulate()} — the only way to validate against real output without ever
   * reconstructing a fudged bar (a wrong low/open/volume would silently change production's OWN
   * internal ATR/volume-ratio/geometry and invalidate the comparison).
   */
  private record SymbolData(
      String symbol,
      List<DailyBar> bars,
      LocalDate[] date,
      double[] close,
      double[] high,
      double[] sma10,
      double[] atr,
      double[] rsRank,
      boolean[] breakoutSignal,
      boolean[] vcpSignal) {}

  private static SymbolData precompute(
      String symbol,
      List<DailyBar> bars,
      VcpDetector vcpDetector,
      ConsolidationBreakout breakoutDetector,
      LocalDate[] rankDates,
      Map<LocalDate, double[]> dist) {
    int n = bars.size();
    double[] close = new double[n];
    double[] high = new double[n];
    double[] low = new double[n];
    double[] volume = new double[n];
    LocalDate[] date = new LocalDate[n];
    for (int i = 0; i < n; i++) {
      DailyBar b = bars.get(i);
      close[i] = b.close();
      high[i] = b.high();
      low[i] = b.low();
      volume[i] = b.volume();
      date[i] = b.date();
    }
    double[] sma50 = sma(close, 50);
    double[] sma200 = sma(close, 200);
    double[] sma10 = sma(close, PARABOLIC_MA);
    double[] high52wIncl = rollingMax(high, 252);
    double[] low52w = rollingMin(low, 252);
    double[] recentHigh = rollingMax(high, 126);
    double[] volRatio50 = volumeRatio(volume, 50);
    double[] turnover20 = avgTurnover(close, volume, TURNOVER_LOOKBACK);
    double[] atr = atr(high, low, close, ATR_PERIOD);

    // rsRank: per-bar cross-sectional percentile (mirrors ManasAroraBacktestService.perBarRsRank)
    double[] rsRank = new double[n];
    Arrays.fill(rsRank, Double.NaN);
    for (int i = RS_LOOKBACK; i < n; i++) {
      LocalDate rd = asOfRankDate(rankDates, date[i]);
      if (rd == null) {
        continue;
      }
      double[] d = dist.get(rd);
      if (d == null || d.length < MIN_DIST) {
        continue;
      }
      double my = weightedRs(close, i);
      if (!Double.isNaN(my)) {
        rsRank[i] = percentileLocal(d, my);
      }
    }

    // weekly geometry via PRODUCTION detectors, unmodified (mirrors ManasAroraSwingBacktest.simulate)
    double[] vcpPivot = new double[n];
    boolean[] isVcp = new boolean[n];
    double[] breakoutPivot = new double[n];
    double curVcpPivot = 0;
    boolean curVcp = false;
    double curBreakoutPivot = 0;
    for (int i = 0; i < n; i++) {
      if (i >= MIN_BARS && (i % WEEKLY == 0 || i == MIN_BARS)) {
        int start = Math.max(0, i - GEO_LOOKBACK + 1);
        List<DailyBar> window = bars.subList(start, i + 1);
        var f = vcpDetector.detect(window);
        curVcp = f.vcp();
        curVcpPivot = f.pivot();
        curBreakoutPivot = breakoutDetector.detect(window).pivot();
      }
      vcpPivot[i] = curVcpPivot;
      isVcp[i] = curVcp;
      breakoutPivot[i] = curBreakoutPivot;
    }

    boolean[] breakoutSignal = new boolean[n];
    boolean[] vcpSignal = new boolean[n];
    for (int i = MIN_BARS; i < n; i++) {
      breakoutSignal[i] =
          entryFires(
              "breakout", i, close, sma50, sma200, high52wIncl, low52w, recentHigh, volRatio50,
              turnover20, rsRank, vcpPivot, isVcp, breakoutPivot);
      vcpSignal[i] =
          entryFires(
              "vcp", i, close, sma50, sma200, high52wIncl, low52w, recentHigh, volRatio50,
              turnover20, rsRank, vcpPivot, isVcp, breakoutPivot);
    }
    return new SymbolData(symbol, bars, date, close, high, sma10, atr, rsRank, breakoutSignal, vcpSignal);
  }

  /** The setup-specific entry gate. Mirrors {@code ManasAroraSwingBacktest.entryFires} (private). */
  private static boolean entryFires(
      String setup, int i, double[] close, double[] sma50, double[] sma200, double[] high52wIncl,
      double[] low52w, double[] recentHigh, double[] volRatio50, double[] turnover20, double[] rsRank,
      double[] vcpPivot, boolean[] isVcp, double[] breakoutPivot) {
    if (!selectionGates(i, close, sma50, sma200, high52wIncl, low52w, recentHigh, rsRank)) {
      return false;
    }
    if (Double.isNaN(turnover20[i]) || turnover20[i] < TURNOVER_FLOOR) {
      return false;
    }
    if (volRatio50[i] <= VOL_MIN) {
      return false;
    }
    return switch (setup) {
      case "breakout" -> crossover(close, breakoutPivot, i);
      case "vcp" -> isVcp[i] && crossover(close, vcpPivot, i);
      default -> false;
    };
  }

  /**
   * The §4.1 selection gates + §1.2 universe gate, for the "rs-turnover" variant (useRealRs=true,
   * rsMin=70). Mirrors {@code ManasAroraSwingBacktest.selectionGates} (private) — delegates the actual
   * gate math to the REUSED, unmodified, package-visible {@link ManasGates}.
   */
  private static boolean selectionGates(
      int i, double[] close, double[] sma50, double[] sma200, double[] high52wIncl, double[] low52w,
      double[] recentHigh, double[] rsRank) {
    double rs = rsRank[i];
    if (Double.isNaN(rs) || rs < RS_MIN) {
      return false;
    }
    boolean[] g =
        ManasGates.gates(
            bd(close[i]), bd(sma50[i]), bd(sma200[i]), i >= 63 ? bd(sma200[i - 63]) : null,
            bd(high52wIncl[i]), bd(low52w[i]), bd(recentHigh[i]), new BigDecimal("30"),
            new BigDecimal("100"));
    if (ManasGates.passed(g) != 6) {
      return false;
    }
    return ManasGates.withinHigh(bd(close[i]), bd(high52wIncl[i]), new BigDecimal("25"));
  }

  private static BigDecimal bd(double v) {
    return Double.isNaN(v) ? null : BigDecimal.valueOf(v);
  }

  /** Mirrors {@code ManasAroraSwingBacktest.crossover} (private). */
  private static boolean crossover(double[] close, double[] level, int i) {
    return i > 0 && level[i] > 0 && close[i - 1] <= level[i] && close[i] > level[i];
  }

  // =================================================================================================
  // RS cross-section (Pass 1) — mirrors ManasAroraBacktestService.computeAll's Pass 1
  // =================================================================================================

  private static LocalDate[] weeklyRankDates(JdbcTemplate jdbc, LocalDate from) {
    List<LocalDate> distinct =
        jdbc.query(
            "SELECT DISTINCT bucket::date AS d FROM candles"
                + " WHERE exchange='NSE' AND interval='1d' AND bucket >= ? ORDER BY d",
            (rs, n) -> rs.getObject("d", LocalDate.class), Date.valueOf(from));
    List<LocalDate> weekly = new ArrayList<>();
    for (int i = 0; i < distinct.size(); i += RANK_CADENCE) {
      weekly.add(distinct.get(i));
    }
    return weekly.toArray(new LocalDate[0]);
  }

  private static LocalDate[] allTradingDates(JdbcTemplate jdbc, LocalDate from) {
    return jdbc
        .query(
            "SELECT DISTINCT bucket::date AS d FROM candles"
                + " WHERE exchange='NSE' AND interval='1d' AND bucket >= ? ORDER BY d",
            (rs, n) -> rs.getObject("d", LocalDate.class), Date.valueOf(from))
        .toArray(new LocalDate[0]);
  }

  private static Map<LocalDate, double[]> rsDistribution(
      JdbcTemplate jdbc, List<String> symbols, LocalDate warmStart, LocalDate[] rankDates) {
    Map<LocalDate, DoubleBag> bags = new HashMap<>();
    for (LocalDate d : rankDates) {
      bags.put(d, new DoubleBag());
    }
    int chunkSize = 50;
    for (int i = 0; i < symbols.size(); i += chunkSize) {
      List<String> chunk = symbols.subList(i, Math.min(i + chunkSize, symbols.size()));
      Map<String, double[][]> byCloses = readClosesBatched(jdbc, chunk, warmStart); // [dates-as-epoch][close]
      for (String symbol : chunk) {
        double[][] s = byCloses.get(symbol);
        if (s == null || s[1].length < MIN_BARS) {
          continue;
        }
        double[] closeArr = s[1];
        LocalDate[] dateArr = new LocalDate[s[0].length];
        for (int k = 0; k < dateArr.length; k++) {
          dateArr[k] = LocalDate.ofEpochDay((long) s[0][k]);
        }
        for (LocalDate rd : rankDates) {
          int idx = asOfIndex(dateArr, rd);
          if (idx < RS_LOOKBACK) {
            continue;
          }
          double rs = weightedRs(closeArr, idx);
          if (!Double.isNaN(rs)) {
            bags.get(rd).add(rs);
          }
        }
      }
    }
    Map<LocalDate, double[]> dist = new HashMap<>();
    bags.forEach((d, bag) -> dist.put(d, bag.sorted()));
    return dist;
  }

  /** Weighted trailing RS. Mirrors {@code ManasAroraBacktestService.weightedRs} (private). */
  private static double weightedRs(double[] close, int i) {
    double r63 = ret(close[i], close[i - 63]);
    double r126 = ret(close[i], close[i - 126]);
    double r189 = ret(close[i], close[i - 189]);
    double r252 = ret(close[i], close[i - 252]);
    return 0.4 * r63 + 0.2 * r126 + 0.2 * r189 + 0.2 * r252;
  }

  private static double ret(double now, double past) {
    return past > 0 ? (now - past) / past : Double.NaN;
  }

  private static double percentileLocal(double[] sorted, double x) {
    return ManasAroraBacktestService.percentile(sorted, x); // reused, package-visible
  }

  private static LocalDate asOfRankDate(LocalDate[] rankDates, LocalDate date) {
    int lo = 0;
    int hi = rankDates.length - 1;
    LocalDate best = null;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (!rankDates[mid].isAfter(date)) {
        best = rankDates[mid];
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return best;
  }

  private static int asOfIndex(LocalDate[] dates, LocalDate d) {
    int lo = 0;
    int hi = dates.length - 1;
    int best = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (!dates[mid].isAfter(d)) {
        best = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return best;
  }

  private static final class DoubleBag {
    private double[] a = new double[16];
    private int size;

    void add(double v) {
      if (size == a.length) {
        a = Arrays.copyOf(a, a.length * 2);
      }
      a[size++] = v;
    }

    double[] sorted() {
      double[] r = Arrays.copyOf(a, size);
      Arrays.sort(r);
      return r;
    }
  }

  // =================================================================================================
  // Rolling-indicator primitives — mirror ManasAroraSwingBacktest's private static helpers verbatim
  // =================================================================================================

  private static double[] sma(double[] v, int period) {
    double[] out = new double[v.length];
    double sum = 0;
    for (int i = 0; i < v.length; i++) {
      sum += v[i];
      if (i >= period) {
        sum -= v[i - period];
      }
      out[i] = i >= period - 1 ? sum / period : Double.NaN;
    }
    return out;
  }

  private static double[] atr(double[] high, double[] low, double[] close, int period) {
    int n = high.length;
    double[] out = new double[n];
    double[] tr = new double[n];
    for (int i = 0; i < n; i++) {
      if (i == 0) {
        tr[i] = high[i] - low[i];
      } else {
        double a = high[i] - low[i];
        double b = Math.abs(high[i] - close[i - 1]);
        double c = Math.abs(low[i] - close[i - 1]);
        tr[i] = Math.max(a, Math.max(b, c));
      }
      out[i] = Double.NaN;
    }
    if (n <= period) {
      return out;
    }
    double sum = 0;
    for (int i = 1; i <= period; i++) {
      sum += tr[i];
    }
    double prev = sum / period;
    out[period] = prev;
    for (int i = period + 1; i < n; i++) {
      prev = (prev * (period - 1) + tr[i]) / period;
      out[i] = prev;
    }
    return out;
  }

  private static double[] rollingMax(double[] v, int period) {
    double[] out = new double[v.length];
    for (int i = 0; i < v.length; i++) {
      if (i < period - 1) {
        out[i] = Double.NaN;
        continue;
      }
      double m = Double.NEGATIVE_INFINITY;
      for (int j = i - period + 1; j <= i; j++) {
        m = Math.max(m, v[j]);
      }
      out[i] = m;
    }
    return out;
  }

  private static double[] rollingMin(double[] v, int period) {
    double[] out = new double[v.length];
    for (int i = 0; i < v.length; i++) {
      if (i < period - 1) {
        out[i] = Double.NaN;
        continue;
      }
      double m = Double.POSITIVE_INFINITY;
      for (int j = i - period + 1; j <= i; j++) {
        m = Math.min(m, v[j]);
      }
      out[i] = m;
    }
    return out;
  }

  private static double[] volumeRatio(double[] v, int lookback) {
    double[] out = new double[v.length];
    for (int i = 0; i < v.length; i++) {
      if (i < lookback) {
        out[i] = 0;
        continue;
      }
      double sum = 0;
      for (int j = i - lookback; j < i; j++) {
        sum += v[j];
      }
      double avg = sum / lookback;
      out[i] = avg > 0 ? v[i] / avg : 0;
    }
    return out;
  }

  private static double[] avgTurnover(double[] close, double[] volume, int lookback) {
    double[] out = new double[close.length];
    for (int i = 0; i < close.length; i++) {
      if (i < lookback - 1) {
        out[i] = Double.NaN;
        continue;
      }
      double sum = 0;
      for (int j = i - lookback + 1; j <= i; j++) {
        sum += close[j] * volume[j];
      }
      out[i] = sum / lookback;
    }
    return out;
  }

  // =================================================================================================
  // Fidelity validation: exact trade-identity match against production's simulate()
  // =================================================================================================

  private record FidelityResult(
      int breakoutChecked, int breakoutMatched, List<String> breakoutMismatches,
      int vcpChecked, int vcpMatched, List<String> vcpMismatches) {}

  /**
   * For every symbol, calls PRODUCTION's unmodified {@code ManasAroraSwingBacktest.simulate()} fed
   * THIS harness's own {@code rsRank} (the only external input {@code simulate} takes as a parameter),
   * getting production's ACTUAL standalone (single-setup, own book) trade sequence for both setups.
   * Independently replays the SAME standalone single-setup lifecycle using this harness's OWN {@code
   * entryFires} + REUSED {@code initialStop}/{@code positionExit}/{@code stopBreached}, and asserts an
   * EXACT trade-for-trade match (entry/exit date, entry/exit price, exit reason) — not merely equal
   * counts. This validates the RS pass, the duplicated entry-signal orchestration, AND the duplicated
   * Chandelier-trail state update all at once, against real production output.
   */
  private static FidelityResult productionFidelity(
      Map<String, SymbolData> data, ManasAroraSwingBacktest sim, LocalDate from,
      VcpDetector vcpDetector, ConsolidationBreakout breakoutDetector) {
    // useRealRs=true at RS_MIN, fed THIS harness's OWN rsRank array (sd.rsRank(), the SAME array used
    // to derive breakoutSignal/vcpSignal at precompute time) — production's simulate() recomputes ALL
    // of its OWN sma/atr/geometry/turnover/volume internally from the REAL, unmodified `sd.bars()`, so
    // the only shared input between "prod" and "mine" is the RS-rank array; everything else production
    // computes independently. A match therefore validates the RS pass AND the duplicated entry/exit
    // orchestration, not just one or the other.
    Variant variant = new Variant("fidelity-check", true, RS_MIN, TURNOVER_FLOOR, false);
    int bChecked = 0;
    int bMatched = 0;
    int vChecked = 0;
    int vMatched = 0;
    List<String> bMismatch = new ArrayList<>();
    List<String> vMismatch = new ArrayList<>();
    for (Map.Entry<String, SymbolData> e : data.entrySet()) {
      String symbol = e.getKey();
      SymbolData sd = e.getValue();
      List<BtTrade> prod =
          sim.simulate(
              symbol, sd.bars(), vcpDetector, breakoutDetector, from, sd.rsRank(), List.of(variant));
      List<String> prodBreakout = tradeSignatures(prod, "breakout");
      List<String> prodVcp = tradeSignatures(prod, "vcp");
      List<String> myBreakout = standaloneReplay(sd, sim, sd.breakoutSignal(), from);
      List<String> myVcp = standaloneReplay(sd, sim, sd.vcpSignal(), from);
      bChecked++;
      vChecked++;
      if (prodBreakout.equals(myBreakout)) {
        bMatched++;
      } else {
        bMismatch.add(symbol + " breakout: prod=" + prodBreakout + " mine=" + myBreakout);
      }
      if (prodVcp.equals(myVcp)) {
        vMatched++;
      } else {
        vMismatch.add(symbol + " vcp: prod=" + prodVcp + " mine=" + myVcp);
      }
    }
    return new FidelityResult(bChecked, bMatched, bMismatch, vChecked, vMatched, vMismatch);
  }

  private static List<String> tradeSignatures(List<BtTrade> trades, String setup) {
    List<String> out = new ArrayList<>();
    for (BtTrade t : trades) {
      if (t.setup().equals(setup)) {
        out.add(
            t.entryDate() + "|" + fmt(t.entryPrice()) + "|" + t.exitDate() + "|" + fmt(t.exitPrice())
                + "|" + t.exitReason());
      }
    }
    return out;
  }

  private static String fmt(double v) {
    return String.format(Locale.ROOT, "%.4f", v);
  }

  /**
   * A standalone (single-setup, own book, no pyramiding) replay using THIS harness's precomputed
   * signal booleans + the REUSED production {@code initialStop}/{@code positionExit}/{@code
   * stopBreached}. Structurally identical to {@code ManasAroraSwingBacktest.simulateSetup}'s
   * single-setup branch, duplicated here only because that method is private.
   */
  private static List<String> standaloneReplay(
      SymbolData sd, ManasAroraSwingBacktest sim, boolean[] signal, LocalDate from) {
    List<String> out = new ArrayList<>();
    int n = sd.close().length;
    boolean open = false;
    int entryIdx = 0;
    double entryPrice = 0;
    double initialStop = 0;
    boolean trailArmed = false;
    double trailStop = 0;
    double runHigh = 0;
    double basisCost = 0;
    for (int i = MIN_BARS; i < n; i++) {
      if (!open) {
        // mirrors ManasAroraSwingBacktest.simulateSetup:276-278 — "if (date.isBefore(from)) continue;"
        if (sd.date()[i].isBefore(from)) {
          continue;
        }
        if (signal[i]) {
          entryIdx = i;
          entryPrice = sd.close()[i];
          initialStop = sim.initialStop(entryPrice, sd.atr()[i]);
          basisCost = sd.close()[i];
          trailArmed = false;
          trailStop = 0;
          runHigh = sd.high()[i];
          open = true;
        }
        continue;
      }
      runHigh = Math.max(runHigh, sd.high()[i]);
      if (!trailArmed && runHigh >= basisCost * (1.0 + TRAIL_ARM_PCT)) {
        trailArmed = true;
        trailStop = basisCost;
      }
      if (trailArmed && !Double.isNaN(sd.atr()[i])) {
        trailStop = Math.max(trailStop, runHigh - ATR_MULT * sd.atr()[i]);
      }
      String reason = sim.positionExit(i, sd.close(), sd.sma10(), trailArmed, trailStop);
      if (reason != null) {
        out.add(sd.date()[entryIdx] + "|" + fmt(entryPrice) + "|" + sd.date()[i] + "|"
            + fmt(sd.close()[i]) + "|" + reason);
        open = false;
        continue;
      }
      if (ManasAroraSwingBacktest.stopBreached(sd.close()[i], initialStop)) {
        out.add(sd.date()[entryIdx] + "|" + fmt(entryPrice) + "|" + sd.date()[i] + "|"
            + fmt(sd.close()[i]) + "|STOP_LOSS");
        open = false;
      }
    }
    return out;
  }

  // =================================================================================================
  // Main replay: two arms, symbol-shared book, real MTM equity + real aggregate risk
  // =================================================================================================

  private static final class OpenLot {
    final String setup;
    final LocalDate entryDate;
    final double entryPrice;
    final long qty;
    final double initialStop;
    boolean trailArmed;
    double trailStop;
    double runHigh;
    final double basisCost;

    OpenLot(String setup, LocalDate entryDate, double entryPrice, long qty, double initialStop, double runHigh) {
      this.setup = setup;
      this.entryDate = entryDate;
      this.entryPrice = entryPrice;
      this.qty = qty;
      this.initialStop = initialStop;
      this.basisCost = entryPrice;
      this.runHigh = runHigh;
    }

    double currentStop() {
      return trailArmed ? Math.max(initialStop, trailStop) : initialStop;
    }

    /**
     * M40 re-measurement (2026-08-02): the stop the aggregate-risk sum should charge this lot under a
     * given modelled basis. {@code TRAILED} is v3's model (and equals production's WARM-cache value);
     * {@code INITIAL} is production on a COLD cache — {@code PaperEmissionGuard.effectiveStop} falling
     * back to the persisted, never-ratcheted {@code paper_positions.stop_loss}.
     */
    double stopFor(StopBasis basis, boolean cacheCold) {
      return switch (basis) {
        case TRAILED -> currentStop();
        case INITIAL -> initialStop;
        case HYBRID -> cacheCold ? initialStop : currentStop();
      };
    }
  }

  /**
   * Which stop the modelled aggregate open-risk sum charges each held lot. Production
   * ({@code PaperEmissionGuard.effectiveStop}, PR #1221) reads {@code ManasGoverningStopCache} when
   * populated and the persisted {@code paper_positions.stop_loss} otherwise, so these three bracket it:
   * {@code TRAILED} = cache always warm (v3's model), {@code INITIAL} = cache always cold,
   * {@code HYBRID} = cold on one session in {@code restartEverySessions}.
   */
  private enum StopBasis {
    TRAILED,
    INITIAL,
    HYBRID
  }

  /**
   * One replay arm's configuration. The v3 pair is {@code arm(name, false/true, TRAILED, 0, 0, set())}
   * — every added field defaults to a no-op so the two original arms stay byte-identical.
   *
   * @param entrySlippageBps the new lot's risk is projected against the FILL, not the candle close —
   *     production's authoritative check runs inside {@code PaperService.openOrder} against
   *     {@code fill.fillPrice()}. Affects ONLY the cap projection, never recorded P&L (both arms stay
   *     gross, so the DELTA is not confounded by a one-sided cost model).
   * @param capExemptKeys {@code symbol@entryDate} keys the cap is waived for — the drop-k lever.
   */
  private record ArmSpec(
      String name,
      boolean enforceAggregateCap,
      StopBasis stopBasis,
      int restartEverySessions,
      double entrySlippageBps,
      java.util.Set<String> capExemptKeys) {}

  private record Refusal(String symbol, String setup, LocalDate date, String reason) {
    String key() {
      return symbol + "@" + date;
    }
  }

  private static final class ClosedOrOpenEntry {
    String symbol;
    String setup;
    LocalDate entryDate;
    LocalDate exitDate;
    Double pnlPct;
    String exitReason;
  }

  private static final class ArmResult {
    List<ClosedOrOpenEntry> closed = new ArrayList<>();
    Map<String, ClosedOrOpenEntry> admittedByKey = new LinkedHashMap<>(); // symbol@entryDate -> entry
    List<Refusal> refusals = new ArrayList<>();
    List<LocalDate> curveDate = new ArrayList<>();
    List<Double> curveEquity = new ArrayList<>();
    List<Double> riskPctAtEntryDecision = new ArrayList<>(); // aggregate risk % BEFORE each admitted entry
    int maxConcurrentOpen = 0;
    int totalAdmitted = 0;
    // Cross-vendor review round 2: the portfolio replay's symbol coverage must be checked against an
    // independent expectation, not assumed from the fidelity check's 2,491/2,491 — that check only
    // exercises the standalone single-setup replay path, never this pointer-advance loop. Tracks every
    // symbol that produced at least one usable bar within [from, ...] (regardless of whether any entry
    // was ever admitted for it) so replay() can assert this against the expected population.
    java.util.Set<String> participatingSymbols = new java.util.HashSet<>();

    Map<String, ClosedOrOpenEntry> allEntriesByKey() {
      return admittedByKey;
    }
  }

  /**
   * The core replay. {@code enforceAggregateCap=false} is today's live rail (MAX_OPEN=7 only, matching
   * {@code RiskService.entryVeto}'s MAX_OPEN check); {@code true} additionally enforces the doctrine's
   * 6% aggregate open-risk cap on every FRESH entry (mirroring {@code
   * ManasPyramidPolicy.breachesRiskCap}'s formula, reproduced verbatim in {@link
   * #wouldBreachAggregateCap}). One book, symbol-shared across both setups (pyramiding off).
   */
  private static ArmResult runArm(
      Map<String, SymbolData> data, LocalDate[] allDates, ManasAroraSwingBacktest sim,
      ArmSpec spec, LocalDate from) {
    boolean enforceAggregateCap = spec.enforceAggregateCap();
    ArmResult r = new ArmResult();
    // BUGFIX (cross-vendor review round 2, Critical): each symbol's bar array starts at `warmStart`
    // (~600 days before `from`, for indicator warmup), but `allDates` starts AT `from`. Initializing
    // every pointer to 0 and only advancing on an EXACT date match meant a symbol with full warm-up
    // history (date[0] << from) could never match any date in `allDates` and its pointer stayed
    // stuck at 0 forever — silently excluding every established symbol from the portfolio replay,
    // leaving only symbols whose FIRST loaded bar happened to already be on/after `from`. Fix:
    // initialize each pointer to the first index on/after `from`, mirroring production's own
    // `date.isBefore(from) -> continue` skip (ManasAroraSwingBacktest.java:276-278) as a one-time
    // starting-point jump instead of a per-iteration check.
    Map<String, Integer> ptr = new HashMap<>();
    for (Map.Entry<String, SymbolData> e : data.entrySet()) {
      LocalDate[] dates = e.getValue().date();
      int idx = 0;
      while (idx < dates.length && dates[idx].isBefore(from)) {
        idx++;
      }
      ptr.put(e.getKey(), idx);
    }
    Map<String, Double> lastClose = new HashMap<>();
    Map<String, OpenLot> open = new HashMap<>();
    double realized = 0;

    StrategyDefinition.SizingSpec sizing =
        new StrategyDefinition.SizingSpec("atr_risk", Map.of("risk_pct_equity", RISK_PCT_EQUITY));

    for (int sessionIdx = 0; sessionIdx < allDates.length; sessionIdx++) {
      LocalDate date = allDates[sessionIdx];
      // M40 re-measurement: was strategy-signal-service restarted since the previous nightly batch?
      // If so ManasGoverningStopCache is EMPTY at this batch's entry pass and every held lot falls back
      // to its persisted (never-ratcheted) initial stop. Deterministic (index-modulo, not RNG) so the
      // run stays byte-reproducible; N=1 means "cold every session".
      boolean cacheCold =
          spec.stopBasis() == StopBasis.HYBRID
              && spec.restartEverySessions() > 0
              && sessionIdx % spec.restartEverySessions() == 0;
      // advance pointers; find today's active bar index per symbol
      Map<String, Integer> todayIdx = new HashMap<>();
      for (Map.Entry<String, SymbolData> e : data.entrySet()) {
        String symbol = e.getKey();
        SymbolData sd = e.getValue();
        int p = ptr.get(symbol);
        if (p < sd.date().length && sd.date()[p].equals(date)) {
          todayIdx.put(symbol, p);
          lastClose.put(symbol, sd.close()[p]);
          ptr.put(symbol, p + 1);
          r.participatingSymbols.add(symbol);
        }
      }
      if (todayIdx.isEmpty()) {
        continue;
      }

      // ---- ENTRY PASS (mirrors SwingBatchEngine.runDaily: entryPass() BEFORE exitPass()) ----------
      double equity = equityOf(realized, open, lastClose);
      List<String> candidateSymbols = new ArrayList<>();
      Map<String, String> candidateSetup = new HashMap<>();
      for (Map.Entry<String, Integer> te : todayIdx.entrySet()) {
        String symbol = te.getKey();
        if (open.containsKey(symbol)) {
          continue; // isAdd && !hasRoom (pyramiding off) -> skip BEFORE any signal evaluation
        }
        int idx = te.getValue();
        if (idx < MIN_BARS) {
          continue;
        }
        SymbolData sd = data.get(symbol);
        boolean b = sd.breakoutSignal()[idx];
        boolean v = sd.vcpSignal()[idx];
        if (b || v) {
          candidateSymbols.add(symbol);
          candidateSetup.put(symbol, b ? "breakout" : "vcp"); // documented tie-break: breakout wins
        }
      }
      // RS-priority ordering for same-day multi-symbol admission (this repo's "realistic-live"
      // convention, per SwingPortfolio's own javadoc — a modeling choice, not a byte-exact replay of
      // the live funnel's own candidate order, which this harness cannot reconstruct; see Open Doubts).
      candidateSymbols.sort(
          Comparator.comparingDouble(
                  (String s) -> {
                    double rs = rsAt(data.get(s), todayIdx.get(s));
                    return Double.isNaN(rs) ? Double.NEGATIVE_INFINITY : rs;
                  })
              .reversed());

      for (String symbol : candidateSymbols) {
        int idx = todayIdx.get(symbol);
        SymbolData sd = data.get(symbol);
        String setup = candidateSetup.get(symbol);
        double entryPrice = sd.close()[idx];
        double atrAtEntry = sd.atr()[idx];
        double initStop = sim.initialStop(entryPrice, atrAtEntry);
        double stopDistance = entryPrice - initStop;
        if (stopDistance <= 0) {
          continue;
        }
        long qty =
            PositionSizer.size(
                sizing,
                new PositionSizer.Inputs(
                    BigDecimal.valueOf(equity), BigDecimal.valueOf(entryPrice),
                    BigDecimal.valueOf(stopDistance), 1));
        if (qty <= 0) {
          continue; // ZERO_SIZE — no entry in either arm, not a cap-driven refusal
        }
        double existingRisk = aggregateRiskInr(open, spec.stopBasis(), cacheCold);
        double riskBeforePct = 100.0 * existingRisk / equity;
        if (open.size() >= MAX_OPEN) {
          r.refusals.add(new Refusal(symbol, setup, date, "MAX_OPEN"));
          continue;
        }
        // The new lot's own contribution is projected against the FILL, not the candle close: PR
        // #1221's authoritative check runs under PaperService.openOrder's book lock against
        // fill.fillPrice(), which for an equity BUY carries the LtpSlippageV1 bps fallback. The
        // request's stop is unchanged (it was computed at emission off the close), so the projected
        // distance widens by exactly the slippage. 0 bps reproduces v3.
        double fillPrice = entryPrice * (1.0 + spec.entrySlippageBps() / 10_000.0);
        double capStopDistance = fillPrice - initStop;
        if (enforceAggregateCap
            && !spec.capExemptKeys().contains(symbol + "@" + date)
            && wouldBreachAggregateCap(
                existingRisk, qty, capStopDistance, equity, AGGREGATE_CAP_PCT)) {
          r.refusals.add(new Refusal(symbol, setup, date, "AGG_CAP"));
          continue;
        }
        // ADMIT
        r.riskPctAtEntryDecision.add(riskBeforePct);
        OpenLot lot = new OpenLot(setup, date, entryPrice, qty, initStop, sd.high()[idx]);
        open.put(symbol, lot);
        r.maxConcurrentOpen = Math.max(r.maxConcurrentOpen, open.size());
        r.totalAdmitted++;
        ClosedOrOpenEntry entry = new ClosedOrOpenEntry();
        entry.symbol = symbol;
        entry.setup = setup;
        entry.entryDate = date;
        r.admittedByKey.put(symbol + "@" + date, entry);
      }

      // ---- EXIT PASS (mirrors SwingBatchEngine.runDaily: exitPass() AFTER entryPass()) -------------
      List<String> toClose = new ArrayList<>();
      for (Map.Entry<String, OpenLot> oe : open.entrySet()) {
        String symbol = oe.getKey();
        OpenLot lot = oe.getValue();
        if (lot.entryDate.equals(date)) {
          continue; // a fresh entry is never exit-evaluated the same session
        }
        Integer idxObj = todayIdx.get(symbol);
        if (idxObj == null) {
          continue; // no bar today for this symbol; carry forward unchanged
        }
        int idx = idxObj;
        SymbolData sd = data.get(symbol);
        lot.runHigh = Math.max(lot.runHigh, sd.high()[idx]);
        if (!lot.trailArmed && lot.runHigh >= lot.basisCost * (1.0 + TRAIL_ARM_PCT)) {
          lot.trailArmed = true;
          lot.trailStop = lot.basisCost;
        }
        if (lot.trailArmed && !Double.isNaN(sd.atr()[idx])) {
          lot.trailStop = Math.max(lot.trailStop, lot.runHigh - ATR_MULT * sd.atr()[idx]);
        }
        String reason = sim.positionExit(idx, sd.close(), sd.sma10(), lot.trailArmed, lot.trailStop);
        if (reason == null && ManasAroraSwingBacktest.stopBreached(sd.close()[idx], lot.initialStop)) {
          reason = "STOP_LOSS";
        }
        if (reason != null) {
          double exitPrice = sd.close()[idx];
          double pnlPct = (exitPrice - lot.entryPrice) / lot.entryPrice * 100.0;
          realized += (exitPrice - lot.entryPrice) * lot.qty;
          ClosedOrOpenEntry entry = r.admittedByKey.get(symbol + "@" + lot.entryDate);
          entry.exitDate = date;
          entry.pnlPct = pnlPct;
          entry.exitReason = reason;
          r.closed.add(entry);
          toClose.add(symbol);
        }
      }
      for (String s : toClose) {
        open.remove(s);
      }

      r.curveDate.add(date);
      r.curveEquity.add(equityOf(realized, open, lastClose));
    }
    return r;
  }

  /** The RS-rank percentile at bar {@code idx} — used ONLY to order same-day multi-SYMBOL admission
   * (RS-priority, this repo's "realistic-live" convention per {@code SwingPortfolio}'s own javadoc;
   * a modeling choice, not a byte-exact replay of the live funnel's own candidate order — see the
   * doc's Open Doubts). */
  private static double rsAt(SymbolData sd, int idx) {
    return sd.rsRank()[idx];
  }

  /** equity = startingCapital + realized + Σ open lots' unrealized MTM (marked at last known close). */
  private static double equityOf(
      double realized, Map<String, OpenLot> open, Map<String, Double> lastClose) {
    double unrealized = 0;
    for (Map.Entry<String, OpenLot> e : open.entrySet()) {
      Double mark = lastClose.get(e.getKey());
      if (mark == null) {
        continue;
      }
      unrealized += (mark - e.getValue().entryPrice) * e.getValue().qty;
    }
    return STARTING_CAPITAL + realized + unrealized;
  }

  private static double aggregateRiskInr(
      Map<String, OpenLot> open, StopBasis basis, boolean cacheCold) {
    double risk = 0;
    for (OpenLot lot : open.values()) {
      risk += Math.max(0, lot.entryPrice - lot.stopFor(basis, cacheCold)) * lot.qty;
    }
    return risk;
  }

  /**
   * §3.4.3 aggregate open-risk-cap check. Reproduced verbatim from {@code
   * ManasPyramidPolicy.breachesRiskCap} (strategy-signal-service, a different Maven module
   * market-data-service cannot import) — the same formula this harness's v1 already used correctly.
   */
  private static boolean wouldBreachAggregateCap(
      double existingRiskInr, long newQty, double stopDistance, double equity, double capPct) {
    if (equity <= 0 || newQty <= 0 || stopDistance <= 0) {
      return false;
    }
    double totalRisk = existingRiskInr + newQty * stopDistance;
    double totalPct = totalRisk / equity * 100.0;
    return totalPct > capPct;
  }

  // =================================================================================================
  // Reporting
  // =================================================================================================

  private static String armSummary(ArmResult r) {
    StringBuilder sb = new StringBuilder();
    sb.append("totalAdmitted=").append(r.totalAdmitted).append(" closed=").append(r.closed.size())
        .append(" openAtEnd=").append(r.admittedByKey.size() - r.closed.size())
        .append(" maxConcurrentOpen=").append(r.maxConcurrentOpen).append("\n");
    Map<String, Integer> refusalCounts = new TreeMap<>();
    for (Refusal ref : r.refusals) {
      refusalCounts.merge(ref.reason, 1, Integer::sum);
    }
    sb.append("refusals: ").append(refusalCounts).append("\n");
    if (r.curveDate.isEmpty()) {
      return sb.toString();
    }
    double startEq = STARTING_CAPITAL;
    double endEq = r.curveEquity.get(r.curveEquity.size() - 1);
    double years =
        Math.max(
            1e-9,
            java.time.temporal.ChronoUnit.DAYS.between(
                    r.curveDate.get(0), r.curveDate.get(r.curveDate.size() - 1))
                / 365.25);
    double cagr = (Math.pow(endEq / startEq, 1.0 / years) - 1.0) * 100.0;
    double maxDd = maxDrawdownPct(r.curveEquity);
    double sharpe = monthlySharpe(r.curveDate, r.curveEquity);
    sb.append(
        String.format(
            Locale.ROOT, "finalEquity=%.0f cagrPct=%.2f maxDrawdownPct=%.2f sharpe=%.2f%n",
            endEq, cagr, maxDd, sharpe));
    return sb.toString();
  }

  private static double maxDrawdownPct(List<Double> eq) {
    double peak = Double.NEGATIVE_INFINITY;
    double maxDd = 0;
    for (double v : eq) {
      peak = Math.max(peak, v);
      if (peak > 0) {
        maxDd = Math.max(maxDd, (peak - v) / peak);
      }
    }
    return maxDd * 100.0;
  }

  private static double monthlySharpe(List<LocalDate> dates, List<Double> eq) {
    Map<java.time.YearMonth, Double> lastOfMonth = new TreeMap<>();
    for (int i = 0; i < dates.size(); i++) {
      lastOfMonth.put(java.time.YearMonth.from(dates.get(i)), eq.get(i));
    }
    List<Double> monthly = new ArrayList<>();
    double prev = STARTING_CAPITAL;
    for (double v : lastOfMonth.values()) {
      monthly.add(v / prev - 1.0);
      prev = v;
    }
    if (monthly.size() < 2) {
      return 0;
    }
    double mean = monthly.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    double var = 0;
    for (double m : monthly) {
      var += (m - mean) * (m - mean);
    }
    var /= (monthly.size() - 1);
    double sd = Math.sqrt(var);
    return sd == 0 ? 0 : mean / sd * Math.sqrt(12.0);
  }

  /**
   * M40 re-measurement: the {@code symbol@date} keys admitted in {@code baseline} but refused by the
   * aggregate cap in {@code arm} — the directly-comparable marginal set (same extraction the v3 doc
   * used inline, lifted to a method so every arm can be reported the same way).
   */
  private static List<String> marginalRefusedKeys(ArmResult baseline, ArmResult arm) {
    Map<String, ClosedOrOpenEntry> baselineEntries = baseline.allEntriesByKey();
    List<String> keys = new ArrayList<>();
    for (Refusal r : arm.refusals) {
      if (r.reason.equals("AGG_CAP") && baselineEntries.containsKey(r.key())) {
        keys.add(r.key());
      }
    }
    return keys;
  }

  /**
   * M40 re-measurement: mean / median / win-rate / best / worst over a marginal-refused key set, plus
   * the full per-trade list. v3 reported the mean and win rate but computed the median in a separate
   * {@code awk} pass; doing it in-harness removes that hand step.
   */
  private static String refusedStats(ArmResult baseline, List<String> keys, boolean listTrades) {
    StringBuilder sb = new StringBuilder();
    Map<String, ClosedOrOpenEntry> be = baseline.allEntriesByKey();
    java.util.Set<LocalDate> sessions = new java.util.TreeSet<>();
    List<Double> pnls = new ArrayList<>();
    for (String key : keys) {
      ClosedOrOpenEntry e = be.get(key);
      sessions.add(e.entryDate);
      if (e.pnlPct != null) {
        pnls.add(e.pnlPct);
      }
      if (listTrades) {
        sb.append(
            String.format(
                Locale.ROOT, "%s %s entry=%s exit=%s pnlPct=%s exitReason=%s%n",
                e.setup, e.symbol, e.entryDate, e.exitDate,
                e.pnlPct == null ? "OPEN-AT-END" : String.format(Locale.ROOT, "%.2f", e.pnlPct),
                e.exitReason == null ? "-" : e.exitReason));
      }
    }
    sb.append("count=").append(keys.size()).append(" distinctSessions=").append(sessions.size())
        .append(" closed=").append(pnls.size()).append("\n");
    if (pnls.isEmpty()) {
      return sb.toString();
    }
    List<Double> sorted = new ArrayList<>(pnls);
    java.util.Collections.sort(sorted);
    int n = sorted.size();
    double median =
        n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    long wins = sorted.stream().filter(v -> v > 0).count();
    long losses = sorted.stream().filter(v -> v < 0).count();
    sb.append(
        String.format(
            Locale.ROOT,
            "meanPnlPct=%.3f medianPnlPct=%.3f winRate=%.2f%% (%d W / %d L / %d flat)"
                + " best=%.2f worst=%.2f%n",
            mean, median, 100.0 * wins / n, wins, losses, n - wins - losses,
            sorted.get(n - 1), sorted.get(0)));
    return sb.toString();
  }

  /**
   * M40 re-measurement (sign robustness): each calendar year's return % for both arms, from their own
   * equity curves, plus the delta. If the whole-portfolio result rests on one year this shows it —
   * v3 reported only the 11-year aggregate, which cannot distinguish "a persistent effect" from
   * "2020 happened".
   */
  private static String perYearDelta(ArmResult baseline, ArmResult arm) {
    Map<Integer, Double> baseLast = lastEquityByYear(baseline);
    Map<Integer, Double> armLast = lastEquityByYear(arm);
    StringBuilder sb = new StringBuilder("year  baselineRet%  shippedRet%   delta(pp)  sign\n");
    double basePrev = STARTING_CAPITAL;
    double armPrev = STARTING_CAPITAL;
    int positive = 0;
    int total = 0;
    for (Integer y : new TreeMap<>(baseLast).keySet()) {
      Double b = baseLast.get(y);
      Double a = armLast.get(y);
      if (b == null || a == null) {
        continue;
      }
      double bRet = (b / basePrev - 1.0) * 100.0;
      double aRet = (a / armPrev - 1.0) * 100.0;
      double d = aRet - bRet;
      total++;
      if (d > 0) {
        positive++;
      }
      sb.append(
          String.format(
              Locale.ROOT, "%d  %11.2f  %11.2f  %10.2f  %s%n", y, bRet, aRet, d, d > 0 ? "+" : "-"));
      basePrev = b;
      armPrev = a;
    }
    sb.append(
        String.format(
            Locale.ROOT, "years favouring the cap: %d / %d%n", positive, total));
    return sb.toString();
  }

  private static Map<Integer, Double> lastEquityByYear(ArmResult r) {
    Map<Integer, Double> out = new TreeMap<>();
    for (int i = 0; i < r.curveDate.size(); i++) {
      out.put(r.curveDate.get(i).getYear(), r.curveEquity.get(i));
    }
    return out;
  }

  private static String riskDistribution(ArmResult r) {
    StringBuilder sb = new StringBuilder();
    if (r.riskPctAtEntryDecision.isEmpty()) {
      return "no admitted entries\n";
    }
    List<Double> sorted = new ArrayList<>(r.riskPctAtEntryDecision);
    java.util.Collections.sort(sorted);
    int n = sorted.size();
    sb.append("n=").append(n).append(" (aggregate risk %% immediately BEFORE each admitted entry)\n");
    double[] pctiles = {0, 10, 25, 50, 75, 90, 95, 99, 100};
    for (double p : pctiles) {
      int idx = (int) Math.min(n - 1, Math.round(p / 100.0 * (n - 1)));
      sb.append(String.format(Locale.ROOT, "  p%.0f = %.3f%%%n", p, sorted.get(idx)));
    }
    long above5 = sorted.stream().filter(v -> v >= 5.0).count();
    long above6 = sorted.stream().filter(v -> v >= 6.0).count();
    sb.append(
        String.format(
            Locale.ROOT, "share with existing risk already >= 5%%: %.2f%%; >= 6%%: %.2f%%%n",
            100.0 * above5 / n, 100.0 * above6 / n));
    return sb.toString();
  }

  // =================================================================================================
  // DB access (plain SQL, deliberately duplicated rather than depending on package-visible readers
  // whose signatures may change — these are simple SELECTs, easy to eyeball-verify against the
  // production query text cited in each method's comment)
  // =================================================================================================

  /** Mirrors {@code ManasAroraBacktestService.eqSymbols} (private). */
  private static List<String> eqSymbols(JdbcTemplate jdbc) {
    return jdbc.queryForList(
        "SELECT DISTINCT c.tradingsymbol FROM candles c JOIN instruments i"
            + " ON i.exchange=c.exchange AND i.tradingsymbol=c.tradingsymbol AND i.instrument_type='EQ'"
            + " WHERE c.interval='1d' AND c.exchange='NSE' ORDER BY c.tradingsymbol",
        String.class);
  }

  /** Mirrors {@code ManasAroraBacktestService.readSeriesBatched} (package-visible; duplicated here to
   * avoid a cross-file signature dependency). */
  private static Map<String, List<DailyBar>> readSeriesBatched(
      JdbcTemplate jdbc, List<String> symbols, LocalDate from) {
    Map<String, List<DailyBar>> out = new LinkedHashMap<>();
    int chunkSize = 50;
    for (int i = 0; i < symbols.size(); i += chunkSize) {
      List<String> chunk = symbols.subList(i, Math.min(i + chunkSize, symbols.size()));
      String inClause = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
      Object[] args = new Object[chunk.size() + 1];
      args[0] = Date.valueOf(from);
      for (int k = 0; k < chunk.size(); k++) {
        args[k + 1] = chunk.get(k);
      }
      jdbc.query(
          "SELECT tradingsymbol, bucket::date AS d, open, high, low, close, volume FROM candles"
              + " WHERE exchange='NSE' AND interval='1d' AND bucket >= ?"
              + " AND tradingsymbol IN (" + inClause + ") ORDER BY tradingsymbol, bucket ASC",
          (RowCallbackHandler)
              rs ->
                  out.computeIfAbsent(rs.getString("tradingsymbol"), k -> new ArrayList<>())
                      .add(
                          new DailyBar(
                              rs.getObject("d", LocalDate.class), rs.getBigDecimal("open").doubleValue(),
                              rs.getBigDecimal("high").doubleValue(), rs.getBigDecimal("low").doubleValue(),
                              rs.getBigDecimal("close").doubleValue(), rs.getLong("volume"))),
          args);
    }
    return out;
  }

  /** Returns {@code symbol -> [epochDays[], closes[]]} for the RS cross-section pass only. */
  private static Map<String, double[][]> readClosesBatched(
      JdbcTemplate jdbc, List<String> chunk, LocalDate from) {
    Map<String, List<double[]>> acc = new LinkedHashMap<>();
    String inClause = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
    Object[] args = new Object[chunk.size() + 1];
    args[0] = Date.valueOf(from);
    for (int k = 0; k < chunk.size(); k++) {
      args[k + 1] = chunk.get(k);
    }
    jdbc.query(
        "SELECT tradingsymbol, bucket::date AS d, close FROM candles"
            + " WHERE exchange='NSE' AND interval='1d' AND bucket >= ?"
            + " AND tradingsymbol IN (" + inClause + ") ORDER BY tradingsymbol, bucket ASC",
        (RowCallbackHandler)
            rs ->
                acc.computeIfAbsent(rs.getString("tradingsymbol"), k -> new ArrayList<>())
                    .add(
                        new double[] {
                          rs.getObject("d", LocalDate.class).toEpochDay(), rs.getBigDecimal("close").doubleValue()
                        }),
        args);
    Map<String, double[][]> out = new LinkedHashMap<>();
    acc.forEach(
        (symbol, rows) -> {
          double[] dates = new double[rows.size()];
          double[] closes = new double[rows.size()];
          for (int i = 0; i < rows.size(); i++) {
            dates[i] = rows.get(i)[0];
            closes[i] = rows.get(i)[1];
          }
          out.put(symbol, new double[][] {dates, closes});
        });
    return out;
  }
}
