package in.arthayantra.strategysignal.insights;

import in.arthayantra.strategysignal.insights.InsightGenerator.GenerationContext;
import in.arthayantra.strategysignal.insights.SellDecisionInputs.SellRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The SELL_DECISION generator (INT design §5.3) — surfaces the durable, not-yet-acknowledged swing
 * SELL verdicts (V037 {@code sell_decisions}, §13 row 8) as attention-queue insights with an
 * acknowledge action. A PURE function of {@link SellDecisionInputs}: one insight per unacknowledged
 * SELL row, at NOTICE, escalating to WARN once it has gone {@code >= escalateSessions} sessions
 * un-acked (the follow-through nudge — a SELL you haven't looked at is louder the longer it waits).
 *
 * <p>{@code /act} with {@code ACK_SELL_DECISION} routes to the EXISTING
 * {@code POST /api/v1/signals/sell-decisions/{id}/ack} (this layer never closes a position — it points
 * at the owner's existing acknowledge endpoint). Deterministic: rows are id-ordered, titles templated.
 */
@Component
public class SellDecisionGenerator implements InsightGenerator {

  /** The persisted sell-decision history every evidence chip is navigable to (§3.4). */
  static final String SELL_ENDPOINT = "/api/v1/signals/sell-decisions";

  private final int escalateSessions;

  public SellDecisionGenerator(InsightProperties props) {
    this.escalateSessions = props.sellDecision().escalateSessions();
  }

  @Override
  public InsightType type() {
    return InsightType.SELL_DECISION;
  }

  @Override
  public List<InsightCandidate> generate(GenerationContext ctx) {
    SellDecisionInputs in = ctx.sellDecision();
    if (in == null || in.sells().isEmpty()) {
      return List.of();
    }
    List<SellRow> sells = new ArrayList<>(in.sells());
    sells.sort(Comparator.comparingLong(SellRow::sellDecisionId));

    List<InsightCandidate> out = new ArrayList<>(sells.size());
    for (SellRow s : sells) {
      out.add(candidate(s));
    }
    return out;
  }

  private InsightCandidate candidate(SellRow s) {
    boolean escalate = s.sessionsOpen() >= escalateSessions;
    Severity severity = escalate ? Severity.WARN : Severity.NOTICE;
    String pct = s.unrealizedPct() == null ? "n/a" : signedPct(s.unrealizedPct());

    String title = s.symbol() + " — " + s.verdict() + " (" + pct + ")";
    String explanation =
        "The "
            + s.book()
            + " swing book flagged "
            + s.symbol()
            + " as "
            + s.verdict()
            + " on "
            + s.runDate()
            + " (entry "
            + plain(s.entryPrice())
            + ", now "
            + plain(s.currentPrice())
            + ", "
            + pct
            + ")"
            + (escalate
                ? " — un-acknowledged for " + s.sessionsOpen() + " session(s)."
                : ".")
            + " Acknowledge to clear.";

    List<Evidence> ev = new ArrayList<>();
    ev.add(
        new Evidence(
            "verdict",
            s.verdict(),
            new Evidence.Source(SELL_ENDPOINT, Map.of("book", s.book()), s.runDate().toString()),
            Map.of("sellDecisionId", s.sellDecisionId(), "signalId", s.signalId())));
    ev.add(Evidence.of("unrealized", pct));
    if (s.sellReason() != null && !s.sellReason().isBlank()) {
      ev.add(Evidence.of("reason", s.sellReason()));
    }

    return new InsightCandidate(
        InsightType.SELL_DECISION,
        severity,
        "book:" + s.book(),
        title,
        explanation,
        List.copyOf(ev),
        null,
        null,
        DataTrust.OK,
        List.of(),
        "SELL_DECISION:" + s.sellDecisionId(),
        null,
        null,
        false);
  }

  private static String signedPct(java.math.BigDecimal pct) {
    String v = pct.stripTrailingZeros().toPlainString();
    return (pct.signum() >= 0 ? "+" : "") + v + "%";
  }

  private static String plain(java.math.BigDecimal v) {
    return v == null ? "n/a" : v.stripTrailingZeros().toPlainString();
  }
}
