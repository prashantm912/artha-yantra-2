package in.arthayantra.strategysignal.insights;

import in.arthayantra.strategysignal.schedule.EveningLegReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled insight sweeps (INT design §2.3). I1: a 15-min data-trust sweep + a 5-min risk sweep
 * (fixed-delay, always-on). I2 adds the calendar-driven sweeps: a 15-min market-hours context sweep
 * (CONTEXT_SHIFT/MARKET_STRUCTURE/REJECTION_NEARMISS), a 16:05-IST EOD sweep (REJECTION_RAIL_TREND +
 * HYGIENE, after the 15:45 EOD jobs settle), a 15:30-IST T-1 expiry sweep (EXPIRY_EVENT), and a
 * weekly Saturday quality-report job (QUALITY_REPORT, §10.2).
 *
 * <p>Split out from {@link InsightEngine} and gated on {@code artha.signals.engine-enabled} (default
 * true) so it loads exactly where the live engine runs — and stays OUT of the engine-disabled paper
 * ITs, which prevents surprise rows landing in the shared IT DB (the #634 discipline: gate the
 * schedule-driven bean; the engine's event listener + read/persist beans stay always-on and load
 * fine with the engine off — asserted by the context-load IT).
 *
 * <p>Large initial delays keep the first fixed-delay sweep well clear of short integration tests.
 * Every sweep is fail-soft (the engine methods swallow their own errors); a scheduler exception is
 * caught here too.
 *
 * <p>⚠️ EVERY sweep here logs at INFO on SUCCESS, including a zero result, and that is deliberate.
 * Until ledger H25 none of them did — only {@code log.warn} on failure — so a sweep that ran and
 * legitimately found nothing was <b>indistinguishable from outside from one that never fired</b>.
 * That cost an owner-requested investigation on 2026-08-18: proving the 18:57 sell-decision sweep
 * had in fact run correctly took a code read plus four DB queries, because the only positive
 * evidence available was a SIBLING method on this same bean having written rows a minute earlier.
 *
 * <p>"0 new / 0 refreshed" is a real answer. Absence of a line means the sweep did not run — with
 * one caveat worth knowing: a container recreate takes {@code docker logs} with it, so that
 * inference holds for the log, not for the log as read after a deploy. Snapshot first.
 */
@Component
@ConditionalOnProperty(name = "artha.signals.engine-enabled", havingValue = "true", matchIfMissing = true)
public class InsightSweeper {

  private static final Logger log = LoggerFactory.getLogger(InsightSweeper.class);

  private final InsightEngine engine;
  private final EveningLegReporter legs;

  public InsightSweeper(InsightEngine engine, EveningLegReporter legs) {
    this.engine = engine;
    this.legs = legs;
  }

  /** 15-min data-trust sweep (§2.3 scheduled sweeps). */
  @Scheduled(fixedDelay = 900_000, initialDelay = 200_000)
  public void trustSweep() {
    try {
      InsightEngine.SweepResult r = engine.runTrustSweep();
      log.info("insight trust sweep: {}", r);
    } catch (RuntimeException e) {
      log.warn("insight trust sweep failed: {}", e.toString());
    }
  }

  /** 5-min risk sweep — RISK_HEAT + RISK_STALE_TICK (§2.3 scheduled sweeps). */
  @Scheduled(fixedDelay = 300_000, initialDelay = 240_000)
  public void riskSweep() {
    try {
      InsightEngine.SweepResult r = engine.runRiskSweep();
      log.info("insight risk sweep: {}", r);
    } catch (RuntimeException e) {
      log.warn("insight risk sweep failed: {}", e.toString());
    }
  }

  /** 15-min market-hours context sweep — CONTEXT_SHIFT + MARKET_STRUCTURE + REJECTION_NEARMISS (§6.2). */
  @Scheduled(cron = "${artha.insights.context-cron:0 */15 9-15 * * MON-FRI}", zone = "Asia/Kolkata")
  public void contextSweep() {
    try {
      InsightEngine.SweepResult r = engine.runContextSweep();
      log.info("insight context sweep: {}", r);
    } catch (RuntimeException e) {
      log.warn("insight context sweep failed: {}", e.toString());
    }
  }

  /** 16:05-IST EOD sweep — REJECTION_RAIL_TREND + HYGIENE (§2.3, after the 15:45 EOD jobs). */
  @Scheduled(cron = "${artha.insights.eod-cron:0 5 16 * * MON-FRI}", zone = "Asia/Kolkata")
  public void eodSweep() {
    try {
      InsightEngine.SweepResult r = engine.runEodSweep();
      log.info("insight EOD sweep: {}", r);
    } catch (RuntimeException e) {
      log.warn("insight EOD sweep failed: {}", e.toString());
    }
  }

  /** 15:30-IST T-1 expiry sweep — EXPIRY_EVENT (§2.2). */
  @Scheduled(cron = "${artha.insights.expiry-cron:0 30 15 * * MON-FRI}", zone = "Asia/Kolkata")
  public void expirySweep() {
    try {
      InsightEngine.SweepResult r = engine.runExpirySweep();
      log.info("insight expiry sweep: {}", r);
    } catch (RuntimeException e) {
      log.warn("insight expiry sweep failed: {}", e.toString());
    }
  }

  /**
   * Weekly quality report — QUALITY_REPORT (§10.2). FRIDAY 18:12 IST since 2026-08-12: it ran
   * Saturday 08:00, and the owner's machine is weekday-only, so it had never run. Friday evening
   * keeps the "end of the week" intent inside the operating window.
   */
  @Scheduled(cron = "${artha.insights.quality-cron:0 12 18 * * FRI}", zone = "Asia/Kolkata")
  public void qualityReport() {
    try {
      InsightEngine.SweepResult r = engine.runQualityReport();
      log.info("insight quality report sweep: {}", r);
    } catch (RuntimeException e) {
      log.warn("insight quality report failed: {}", e.toString());
    }
  }

  /** 18:56-IST strategy-evidence sweep — STRATEGY_EVIDENCE, after the 18:55 graduation eval (§5.2). */
  @Scheduled(cron = "${artha.insights.strategy-evidence-cron:0 56 18 * * MON-FRI}", zone = "Asia/Kolkata")
  public void strategyEvidenceSweep() {
    // Reported to market-data's 18:59 evening-chain check (review Major C, 2026-08-17), which
    // announced "safe to shut down" without being able to see this sweep at all. The reporter keeps
    // the previous behaviour exactly — run the body, catch RuntimeException, log a warn — and adds a
    // ledger row for the outcome. It never throws, so the schedule is as safe as it was.
    // ⚠️ MERGE (2026-08-21): main added the SweepResult log line after this branch forked, and
    // report() takes a Runnable — so `engine::runStrategyEvidenceSweep` would DISCARD the result
    // and silently drop that log. Wrapped in a lambda so both survive: the ledger row AND the
    // result. A method reference here is the quiet regression.
    legs.report(
        EveningLegReporter.SOURCE_INSIGHT_STRATEGY_EVIDENCE,
        () -> log.info("insight strategy-evidence sweep: {}", engine.runStrategyEvidenceSweep()));
  }

  /** 18:57-IST sell-decision sweep — SELL_DECISION, after the swing batch persists V037 (§5.3). */
  @Scheduled(cron = "${artha.insights.sell-decision-cron:0 57 18 * * MON-FRI}", zone = "Asia/Kolkata")
  public void sellDecisionSweep() {
    // The sharpest of the five: it STARTS two minutes before the 18:59 check that announced the
    // chain finished. Same reporting wrapper, same swallow-and-warn behaviour as before, and the
    // same lambda-not-method-reference reason as above — main's result log must survive the merge.
    legs.report(
        EveningLegReporter.SOURCE_INSIGHT_SELL_DECISION,
        () -> log.info("insight sell-decision sweep: {}", engine.runSellDecisionSweep()));
  }
}
