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
      engine.runTrustSweep();
    } catch (RuntimeException e) {
      log.warn("insight trust sweep failed: {}", e.toString());
    }
  }

  /** 5-min risk sweep — RISK_HEAT + RISK_STALE_TICK (§2.3 scheduled sweeps). */
  @Scheduled(fixedDelay = 300_000, initialDelay = 240_000)
  public void riskSweep() {
    try {
      engine.runRiskSweep();
    } catch (RuntimeException e) {
      log.warn("insight risk sweep failed: {}", e.toString());
    }
  }

  /** 15-min market-hours context sweep — CONTEXT_SHIFT + MARKET_STRUCTURE + REJECTION_NEARMISS (§6.2). */
  @Scheduled(cron = "${artha.insights.context-cron:0 */15 9-15 * * MON-FRI}", zone = "Asia/Kolkata")
  public void contextSweep() {
    try {
      engine.runContextSweep();
    } catch (RuntimeException e) {
      log.warn("insight context sweep failed: {}", e.toString());
    }
  }

  /** 16:05-IST EOD sweep — REJECTION_RAIL_TREND + HYGIENE (§2.3, after the 15:45 EOD jobs). */
  @Scheduled(cron = "${artha.insights.eod-cron:0 5 16 * * MON-FRI}", zone = "Asia/Kolkata")
  public void eodSweep() {
    try {
      engine.runEodSweep();
    } catch (RuntimeException e) {
      log.warn("insight EOD sweep failed: {}", e.toString());
    }
  }

  /** 15:30-IST T-1 expiry sweep — EXPIRY_EVENT (§2.2). */
  @Scheduled(cron = "${artha.insights.expiry-cron:0 30 15 * * MON-FRI}", zone = "Asia/Kolkata")
  public void expirySweep() {
    try {
      engine.runExpirySweep();
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
      engine.runQualityReport();
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
    legs.report(EveningLegReporter.SOURCE_INSIGHT_STRATEGY_EVIDENCE, engine::runStrategyEvidenceSweep);
  }

  /** 18:57-IST sell-decision sweep — SELL_DECISION, after the swing batch persists V037 (§5.3). */
  @Scheduled(cron = "${artha.insights.sell-decision-cron:0 57 18 * * MON-FRI}", zone = "Asia/Kolkata")
  public void sellDecisionSweep() {
    // The sharpest of the five: it STARTS two minutes before the 18:59 check that announced the
    // chain finished. Same reporting wrapper, same swallow-and-warn behaviour as before.
    legs.report(EveningLegReporter.SOURCE_INSIGHT_SELL_DECISION, engine::runSellDecisionSweep);
  }
}
