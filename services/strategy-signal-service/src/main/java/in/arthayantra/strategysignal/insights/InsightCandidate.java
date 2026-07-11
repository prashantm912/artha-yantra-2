package in.arthayantra.strategysignal.insights;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A generator's output BEFORE the engine stamps identity + persistence (INT design §9.2). Generators
 * are pure ({@code inputs → candidates}); the engine turns each candidate into a persisted
 * {@link Insight} by adding the id, {@code generatedAt}, {@code engineVersion}, {@code configHash},
 * the {@code OPEN} status and the resolved {@code cooldownUntil}. Because a candidate carries no id /
 * timestamp / engine identity, identical inputs produce an identical candidate — the unit the golden
 * test asserts byte-identity on (§10.1).
 *
 * @param cooldownMinutes per-type suppression window (§2.5.2); {@code null} = no cooldown
 * @param suppressed pre-marked muted (rare — usually the engine decides via dedupe/cooldown)
 */
public record InsightCandidate(
    InsightType type,
    Severity severity,
    String scope,
    String title,
    String explanation,
    List<Evidence> evidence,
    BigDecimal priority,
    PriorityDetail priorityDetail,
    DataTrust dataTrust,
    List<String> trustReasons,
    String dedupeKey,
    Integer cooldownMinutes,
    OffsetDateTime expiresAt,
    boolean suppressed) {

  /** Builder-lite for the common case (no priority, no cooldown, not pre-suppressed). */
  public static InsightCandidate simple(
      InsightType type,
      Severity severity,
      String scope,
      String title,
      String explanation,
      List<Evidence> evidence,
      DataTrust dataTrust,
      List<String> trustReasons,
      String dedupeKey) {
    return new InsightCandidate(
        type, severity, scope, title, explanation, evidence, null, null, dataTrust, trustReasons,
        dedupeKey, null, null, false);
  }
}
