package in.arthayantra.strategysignal.insights;

import in.arthayantra.strategysignal.insights.InsightGenerator.GenerationContext;
import in.arthayantra.strategysignal.insights.StrategyEvidenceInputs.BoardRow;
import in.arthayantra.strategysignal.insights.StrategyEvidenceInputs.Criterion;
import in.arthayantra.strategysignal.insights.StrategyEvidenceInputs.PriorSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The STRATEGY_EVIDENCE generator (INT design §5.2) — makes the graduation board's EVIDENCE legible
 * without re-scoring it (§0 hard boundary: the metrics ARE {@code GraduationService}'s). A PURE
 * function of {@link StrategyEvidenceInputs}: for each live strategy it emits
 *
 * <ol>
 *   <li>one <b>suppressed-INFO snapshot</b> row (dedupe-keyed per IST day) that records today's board
 *       — the next day's baseline. The always-write is what guarantees "yesterday exists" even on a
 *       no-change night, so the crossing compare needs NO new table (§5.2); and
 *   <li>when a criterion or the derived stage <b>crossed</b> vs yesterday's snapshot, one visible
 *       STRATEGY_EVIDENCE insight — <b>NOTICE up</b> ("crossed PF 1.3 — TAKE_ELIGIBLE") or <b>WARN
 *       down</b> ("dropped below PF 1.3 after 3 losses"). Nothing fires on the first-ever night (no
 *       prior to compare).
 * </ol>
 *
 * <p>Deterministic: strategies are processed slug-ordered, criteria in board order, titles/explanations
 * are templated string interpolation over the inputs — identical inputs replay byte-identically
 * (golden-testable, §10.1).
 */
@Component
public class StrategyEvidenceGenerator implements InsightGenerator {

  /** The graduation endpoint every crossing/snapshot evidence chip is navigable to (§3.4). */
  static final String BOARD_ENDPOINT = "/api/v1/strategies/graduation";

  /** Snapshot evidence label for the derived stage — {@link StrategyEvidenceReader} parses it back. */
  static final String LABEL_STAGE = "stage";

  /** Snapshot evidence label prefix for a criterion's pass/fail — value is {@code "pass"}/{@code "fail"}. */
  static final String CRITERION_PREFIX = "criterion:";

  @Override
  public InsightType type() {
    return InsightType.STRATEGY_EVIDENCE;
  }

  @Override
  public List<InsightCandidate> generate(GenerationContext ctx) {
    StrategyEvidenceInputs in = ctx.strategyEvidence();
    if (in == null || in.board().isEmpty()) {
      return List.of();
    }
    String asOf = ctx.now().toString();
    List<BoardRow> board = new ArrayList<>(in.board());
    board.sort(Comparator.comparing(BoardRow::slug).thenComparing(r -> r.strategyId().toString()));

    List<InsightCandidate> out = new ArrayList<>();
    for (BoardRow row : board) {
      out.add(snapshot(row, in.istDate().toString()));
      PriorSnapshot prior = in.yesterday().get(row.strategyId());
      if (prior != null) {
        crossing(row, prior, in, asOf).ifPresent(out::add);
      }
    }
    return out;
  }

  /** The always-written suppressed-INFO baseline row (next day's "yesterday"). */
  private InsightCandidate snapshot(BoardRow row, String istDate) {
    List<Evidence> ev = new ArrayList<>();
    ev.add(Evidence.of(LABEL_STAGE, row.stage()));
    ev.add(Evidence.of("trades", String.valueOf(row.trades())));
    ev.add(Evidence.of("profit factor", plain(row.profitFactor())));
    ev.add(Evidence.of("expectancy", plain(row.expectancy())));
    ev.add(Evidence.of("max drawdown %", plain(row.maxDrawdownPct())));
    for (Criterion c : row.criteria()) {
      ev.add(Evidence.of(CRITERION_PREFIX + c.name(), c.pass() ? "pass" : "fail"));
    }
    String explanation =
        row.slug()
            + " graduation snapshot: stage "
            + row.stage()
            + ", "
            + row.trades()
            + " closed trades, PF "
            + plain(row.profitFactor())
            + ", expectancy "
            + plain(row.expectancy())
            + ", max drawdown "
            + plain(row.maxDrawdownPct())
            + "%.";
    return new InsightCandidate(
        InsightType.STRATEGY_EVIDENCE,
        Severity.INFO,
        "strategy:" + row.strategyId(),
        row.slug() + " graduation snapshot " + istDate,
        explanation,
        List.copyOf(ev),
        null,
        null,
        DataTrust.OK,
        List.of(),
        "STRATEGY_EVIDENCE_SNAP:strategy:" + row.strategyId() + ":" + istDate,
        null,
        null,
        true);
  }

  /** The visible crossing insight (NOTICE up / WARN down), or empty when nothing crossed. */
  private java.util.Optional<InsightCandidate> crossing(
      BoardRow row, PriorSnapshot prior, StrategyEvidenceInputs in, String asOf) {
    Criterion downCrit = null;
    Criterion upCrit = null;
    for (Criterion c : row.criteria()) { // board order → deterministic pick
      boolean wasPass = Boolean.TRUE.equals(prior.criteriaPass().get(c.name()));
      if (wasPass && !c.pass() && downCrit == null) {
        downCrit = c;
      }
      if (!wasPass && c.pass() && upCrit == null) {
        upCrit = c;
      }
    }
    boolean stageDropped = "TAKE_ELIGIBLE".equals(prior.stage()) && !"TAKE_ELIGIBLE".equals(row.stage());
    boolean stageRose = !"TAKE_ELIGIBLE".equals(prior.stage()) && "TAKE_ELIGIBLE".equals(row.stage());

    boolean down = downCrit != null || stageDropped;
    boolean up = upCrit != null || stageRose;
    if (!down && !up) {
      return java.util.Optional.empty();
    }

    // DOWN outranks UP: a lost bar is the more urgent signal (§5.2 "dropped below … WARN").
    Severity severity = down ? Severity.WARN : Severity.NOTICE;
    Criterion crit = down ? downCrit : upCrit;
    String critLabel = crit != null ? human(crit) : "graduation stage";

    String title;
    String explanation;
    if (down) {
      title = row.slug() + " dropped below " + critLabel;
      explanation =
          row.slug()
              + " fell below "
              + critLabel
              + " ("
              + (crit != null ? crit.actual() + " vs " + crit.required() : "stage " + row.stage())
              + ") after previously holding it — now stage "
              + row.stage()
              + " over "
              + row.trades()
              + " closed trades.";
    } else {
      title =
          row.slug()
              + " crossed "
              + critLabel
              + " ("
              + row.trades()
              + " trades)"
              + (stageRose ? " — TAKE_ELIGIBLE" : "");
      explanation =
          row.slug()
              + " now passes "
              + critLabel
              + " ("
              + (crit != null ? crit.actual() + " vs " + crit.required() : "stage " + row.stage())
              + ") over "
              + row.trades()
              + " closed trades — stage "
              + row.stage()
              + (in.graduated().contains(row.strategyId()) ? " (GRADUATED)" : "")
              + ".";
    }

    List<Evidence> ev = new ArrayList<>();
    ev.add(Evidence.sourced(LABEL_STAGE, row.stage(), BOARD_ENDPOINT, asOf));
    if (crit != null) {
      ev.add(Evidence.sourced(critLabel, crit.actual() + " (bound " + crit.required() + ")", BOARD_ENDPOINT, asOf));
    }
    ev.add(Evidence.of("closed trades", String.valueOf(row.trades())));

    return java.util.Optional.of(
        new InsightCandidate(
            InsightType.STRATEGY_EVIDENCE,
            severity,
            "strategy:" + row.strategyId(),
            title,
            explanation,
            List.copyOf(ev),
            null,
            null,
            DataTrust.OK,
            List.of(),
            "STRATEGY_EVIDENCE:strategy:" + row.strategyId() + ":" + in.istDate(),
            null,
            null,
            false));
  }

  /** Human criterion label, threshold inline (e.g. {@code profitFactor >= 1.3} → {@code "PF 1.3"}). */
  private static String human(Criterion c) {
    String bound = c.required().replaceAll("^[<>=\\s]+", "").trim();
    String name =
        switch (c.name()) {
          case "profitFactor" -> "PF";
          case "trades" -> "trade count";
          case "expectancy" -> "expectancy";
          case "maxDrawdownPct" -> "max drawdown";
          default -> c.name();
        };
    return bound.isBlank() ? name : name + " " + bound;
  }

  private static String plain(java.math.BigDecimal v) {
    return v == null ? "n/a" : v.stripTrailingZeros().toPlainString();
  }
}
