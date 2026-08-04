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
 * positions to the health/data problem keys). One insight per (POSITION, IST-day), 15-min cooldown.
 *
 * <p><b>Why the dedupe key carries the position id.</b> It used to be {@code (instrument, IST-day)},
 * which is NOT a position identity: {@code uq_paper_positions_open} is
 * {@code (book, exchange, tradingsymbol, side) WHERE status='OPEN'} (V021), so one instrument can
 * legitimately back several open bracketed positions at once — two BOOKS holding the same symbol (V021
 * added the book column for exactly that: "two books may each hold the same (exchange, symbol, side)
 * long at once"), or one book holding both sides. Every such position produced a candidate with the
 * SAME key, and {@link InsightRepository#insertOrRefresh} upserts on
 * {@code ON CONFLICT (dedupe_key) WHERE status='OPEN'} — so the second stalled bracket was folded into
 * the first as a "refresh" and silently vanished. Keying on the position id is correct under ANY
 * open-position uniqueness rule (the row id IS the identity), so a future scoping change cannot
 * re-open this hole. Cost: alert identity is tied to a row id, so closing and re-opening a position on
 * the same symbol mints a NEW key and may re-alert inside what would have been the old cooldown —
 * intended, since that is a genuinely different bracket at risk. Book + side ride along because
 * {@code dedupe_key} is itself an operator-facing surface (the quality report groups suppressed counts
 * by it) and a bare row id names nothing.
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
              bracket + " evaluation degraded on " + b.exchange() + ":" + b.tradingsymbol()
                  + " (" + b.book() + " " + b.side() + ") — feed stalled",
              "The " + b.book() + " book holds a bracketed " + b.side() + " position on " + b.exchange()
                  + ":" + b.tradingsymbol() + " but the live feed is stalled (" + b.detail()
                  + "), so its " + bracket + " cannot be evaluated on fresh bars.",
              List.of(
                  Evidence.sourced("feed health", b.detail(), "/api/v1/market/health/data", ctx.now().toString()),
                  Evidence.of("bracket", bracket + " on " + b.side() + " " + b.tradingsymbol())),
              null, null, DataTrust.BLOCKED,
              List.of("live-capture stalled on " + b.staleKey()),
              "RISK_STALE_TICK:" + b.staleKey() + ":" + b.book() + ":" + b.side() + ":"
                  + b.positionId() + ":" + istDay,
              COOLDOWN_MINUTES, null, false));
    }
    return out;
  }
}
