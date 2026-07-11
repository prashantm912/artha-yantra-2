package in.arthayantra.strategysignal.insights;

import in.arthayantra.common.web.time.Ist;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * RISK_STALE_TICK (INT design §2.2/§7.4, the interim health/data trigger): on the risk sweep, warn
 * when a held BRACKETED paper position's instrument is flagged by the live-capture canary — the
 * bracket's bar-driven SL/TP evaluation is degraded while the feed is stalled. PURE over the parsed
 * {@link StaleTickSnapshot} ({@link PortfolioReader} + {@link ContextClient} matched open bracketed
 * positions to the health/data problem keys). One insight per (instrument, IST-day), 15-min cooldown.
 *
 * <p><b>Interim (§12).</b> The health/data canary proves a BAR stall, not a raw tick starvation (a
 * silently-dead token yields no problem row); this is the honest subset it can prove today, upgrading
 * to the V3 bracket-starvation counter when that lands.
 */
@Component
public class StaleTickGenerator implements InsightGenerator {

  private static final int COOLDOWN_MINUTES = 15;

  @Override
  public InsightType type() {
    return InsightType.RISK_STALE_TICK;
  }

  @Override
  public List<InsightCandidate> generate(GenerationContext ctx) {
    StaleTickSnapshot snap = ctx.staleTick();
    if (snap == null || snap.stalled() == null || snap.stalled().isEmpty()) {
      return List.of();
    }
    LocalDate istDay = ctx.now().atZoneSameInstant(Ist.ZONE).toLocalDate();
    List<InsightCandidate> out = new ArrayList<>();
    for (StaleTickSnapshot.StaleBracket b : snap.stalled()) {
      String bracket = b.hasStop() && b.hasTarget() ? "SL/TP" : b.hasStop() ? "SL" : "TP";
      out.add(
          new InsightCandidate(
              InsightType.RISK_STALE_TICK,
              Severity.WARN,
              "book:" + b.book(),
              bracket + " evaluation degraded on " + b.exchange() + ":" + b.tradingsymbol() + " — feed stalled",
              "The " + b.book() + " book holds a bracketed " + b.side() + " position on " + b.exchange()
                  + ":" + b.tradingsymbol() + " but the live feed is stalled (" + b.detail()
                  + "), so its " + bracket + " cannot be evaluated on fresh bars.",
              List.of(
                  Evidence.sourced("feed health", b.detail(), "/api/v1/market/health/data", ctx.now().toString()),
                  Evidence.of("bracket", bracket + " on " + b.side() + " " + b.tradingsymbol())),
              null, null, DataTrust.BLOCKED,
              List.of("live-capture stalled on " + b.staleKey()),
              "RISK_STALE_TICK:" + b.staleKey() + ":" + istDay, COOLDOWN_MINUTES, null, false));
    }
    return out;
  }
}
