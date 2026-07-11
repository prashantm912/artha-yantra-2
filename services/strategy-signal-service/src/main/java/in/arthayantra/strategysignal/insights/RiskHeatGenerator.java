package in.arthayantra.strategysignal.insights;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * RISK_HEAT (INT design §2.2/§3.2 risk row): a per-book warning when open-position SPAN margin heat
 * or same-underlying+side concentration crosses a config threshold. Display-only — read from the
 * persisted paper-margin annotations (never a live margin call, never a paper mutation). Cooldown
 * (default 15 min, §2.5.2) keeps a hot book from re-firing every sweep.
 */
@Component
public class RiskHeatGenerator implements InsightGenerator {

  private final InsightProperties.Risk cfg;

  public RiskHeatGenerator(InsightProperties props) {
    this.cfg = props.risk();
  }

  @Override
  public InsightType type() {
    return InsightType.RISK_HEAT;
  }

  @Override
  public List<InsightCandidate> generate(GenerationContext ctx) {
    if (ctx.bookHeats() == null || ctx.bookHeats().isEmpty()) {
      return List.of();
    }
    List<InsightCandidate> out = new ArrayList<>();
    for (BookHeat bh : ctx.bookHeats()) {
      boolean critical = isCritical(bh);
      boolean warn = critical || isWarn(bh);
      boolean concentrated = bh.maxConcentration() >= cfg.concentrationMin();
      if (!warn && !concentrated) {
        continue;
      }
      List<Evidence> ev = new ArrayList<>();
      String heatText;
      if (bh.heatPct() != null) {
        heatText = plain(bh.heatPct()) + "%" + (bh.capPct() != null ? " of " + plain(bh.capPct()) + "% cap" : " of equity");
        ev.add(Evidence.sourced("book heat", heatText, "/api/v1/paper/margin-heat", ctx.now().toString()));
      } else {
        heatText = "unpriced";
        ev.add(Evidence.of("book heat", "unpriced"));
      }
      if (concentrated) {
        ev.add(Evidence.of("concentration", bh.maxConcentration() + "× " + bh.concentrationLeg()));
      }
      ev.add(Evidence.of("open positions", String.valueOf(bh.openPositions())));

      Severity severity = critical ? Severity.WARN : Severity.NOTICE;
      String title =
          warn
              ? bh.book() + " book heat " + heatText
              : bh.book() + " book concentration " + bh.maxConcentration() + "× " + bh.concentrationLeg();
      String explanation =
          "The " + bh.book() + " book holds " + bh.openPositions() + " open position(s)"
              + (bh.heatPct() != null ? " at " + heatText + " margin heat" : "")
              + (concentrated ? "; " + bh.maxConcentration() + " share " + bh.concentrationLeg() : "")
              + ". Display-only — no entry is blocked (risk enforcement is separate).";
      out.add(
          new InsightCandidate(
              InsightType.RISK_HEAT,
              severity,
              "book:" + bh.book(),
              title,
              explanation,
              List.copyOf(ev),
              null,
              null,
              DataTrust.OK,
              List.of(),
              "RISK_HEAT:" + bh.book(),
              cfg.cooldownMinutes(),
              null,
              false));
    }
    return out;
  }

  private boolean isCritical(BookHeat bh) {
    if (bh.heatPct() == null) {
      return false;
    }
    return bh.heatPct().compareTo(cfg.criticalPct()) >= 0
        || (bh.capPct() != null && bh.heatPct().compareTo(bh.capPct()) >= 0);
  }

  private boolean isWarn(BookHeat bh) {
    if (bh.heatPct() == null) {
      return false;
    }
    if (bh.heatPct().compareTo(cfg.warnPct()) >= 0) {
      return true;
    }
    return bh.capPct() != null
        && bh.heatPct().compareTo(bh.capPct().multiply(cfg.capWarnFraction())) >= 0;
  }

  private static String plain(BigDecimal v) {
    return v == null ? "n/a" : v.stripTrailingZeros().toPlainString();
  }
}
