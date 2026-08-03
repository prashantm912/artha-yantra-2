package in.arthayantra.strategysignal.signals;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read surface for the confluence-gate REJECTION diagnostics: every scalper chart-entry the live
 * §12.3 gate blocked, with the first failing rail + margin, the full dot-by-dot confluence, and the
 * raw OI/macro/chart context. Powers the "Rejections" analysis page. Live-only data (no rows on
 * backtest).
 *
 * <p>Every response is a typed record (ledger D3 slice 1). The two list bodies used to be {@code
 * Map<String, Object>} under a comment claiming that made response keys safe from drifting the
 * OpenAPI spec — which is exactly backwards: springdoc cannot enumerate a Map, so a renamed or
 * removed key here passed the breaking-change gate unseen. Typing them is what makes the gate work.
 */
@RestController
@RequestMapping("/api/v1/signal-rejections")
public class SignalRejectionsController {

  private final SignalRejectionRepository repository;
  private final ShadowPositionRepository shadows;
  private final DotHealthCanary dotHealth;
  private final StrategyEvalDenominatorRepository denominators;

  /** Wires the repositories + the dot canary. */
  public SignalRejectionsController(
      SignalRejectionRepository repository,
      ShadowPositionRepository shadows,
      DotHealthCanary dotHealth,
      StrategyEvalDenominatorRepository denominators) {
    this.repository = repository;
    this.shadows = shadows;
    this.dotHealth = dotHealth;
    this.denominators = denominators;
  }

  /**
   * Per-DOT input liveness over today's newest rejections (roadmap F4 v2): which gate inputs are
   * live vs dead, and which are REQUIRED alive. The 09:42 agent reads this instead of hand-SQL.
   */
  @GetMapping("/dot-health")
  public DotHealthCanary.DotHealth dotHealth() {
    return dotHealth.evaluate();
  }

  /**
   * Paged/filtered rejection history, newest first.
   *
   * <p>{@code degraded} (F5 U3) narrows to rows whose gate inputs were absent when the bar was
   * scored — omit it for every row, {@code true} for the compromised ones, {@code false} for the
   * clean ones. See {@link DataHealthFlags} for what a flag does and does not mean.
   */
  @GetMapping
  public SignalViews.RejectionPage list(
      @RequestParam(required = false) UUID strategyVersionId,
      @RequestParam(required = false) String rail,
      @RequestParam(required = false) String exchange,
      @RequestParam(required = false) String tradingsymbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to,
      @RequestParam(required = false) Boolean degraded,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int boundedLimit = Math.min(Math.max(limit, 1), 500);
    int boundedOffset = Math.max(offset, 0);
    List<SignalRejectionRepository.RejectionRow> items =
        repository.list(
            strategyVersionId, rail, exchange, tradingsymbol, from, to, degraded, boundedLimit,
            boundedOffset);
    return new SignalViews.RejectionPage(items, boundedLimit, boundedOffset);
  }

  /**
   * The per-rail block rollup (which condition blocks most) over an optional window.
   *
   * <p>{@code strategySlug} narrows it to ONE strategy — what the funnel view needs to fan the
   * confluence-blocked bucket out by rail. The slug is the stable identity across republishes;
   * {@code strategyVersionId} narrows to one published version instead and the two compose.
   */
  @GetMapping("/rail-counts")
  public SignalViews.RailCountList railCounts(
      @RequestParam(required = false) UUID strategyVersionId,
      @RequestParam(required = false) String strategySlug,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to) {
    return new SignalViews.RailCountList(
        repository.railCounts(strategyVersionId, strategySlug, from, to));
  }

  /**
   * The per-strategy evaluation FUNNEL for one IST session date — signal-analysis README §7 row 7,
   * built on the V053 denominator (F5 unit U2) rather than one inferred from the 3m grid.
   *
   * <p>Every bar the engine evaluates lands in exactly one {@code SignalEngine.Outcome} bucket, so
   * the buckets for a day ARE the funnel: their sum is the true denominator and {@code fired} is the
   * survivor count. The caller orders the tags into stages; this endpoint stays a faithful read of
   * the table so a tag added later still arrives.
   *
   * <p>An empty {@code items} means the rollup wrote nothing for that date — a down stack, a date
   * before V053, or one past retention. It does NOT mean the strategies evaluated nothing, and the
   * UI must not render it as zeros.
   *
   * @param date the IST SESSION date (the date of the evaluated bars), not a UTC calendar date
   */
  @GetMapping("/eval-funnel")
  public SignalViews.EvalFunnel evalFunnel(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return new SignalViews.EvalFunnel(
        date, denominators.bootCount(date), denominators.outcomeCounts(date));
  }

  /** The typed shadow-league envelope (the Map-return ratchet forbids new Map endpoints). */
  public record ShadowSummaryResponse(List<ShadowPositionRepository.VariantSummary> items) {}

  /**
   * The shadow-book league table (roadmap F1): per-variant open/closed/wins/losses + realized
   * points over an optional opened-at window — champion vs challenger configs on identical data.
   * Variants are GLOBAL (EVO E3 §11) so the unfiltered league aggregates cross-strategy PnL under
   * one variant name; {@code strategySlug} narrows it to one strategy's book — the per-strategy
   * read campaign analysis pairs with.
   */
  @GetMapping("/shadow-summary")
  public ShadowSummaryResponse shadowSummary(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to,
      @RequestParam(required = false) String strategySlug) {
    return new ShadowSummaryResponse(shadows.variantSummary(from, to, strategySlug));
  }
}
