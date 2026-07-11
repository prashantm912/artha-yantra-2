package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * The priority explain contract (INT design §3.4) — mirrors the frozen ScoreBreakdown convention:
 * {@code score = Σ points}, each component carrying {@code weight × c = points} plus the named
 * evidence behind its {@code c}. Serialized into {@code insights.priority_detail} JSONB and rendered
 * by the FE explain drawer (§8.3). Every number has a source; every source is navigable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PriorityDetail(
    BigDecimal score, String band, BigDecimal trustCap, List<Component> components) {

  /** One weighted priority component (§3.2). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Component(
      String key, BigDecimal weight, BigDecimal c, BigDecimal points, List<Evidence> evidence) {}
}
