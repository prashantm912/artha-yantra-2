package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * M40 Critical 3 fix, round 3 (owner ruling, 2026-08-02 — supersedes round 2's dedicated-column
 * design): the Manas aggregate open-risk cap's view of each held position's CURRENT governing stop
 * (the daily Chandelier trail), held IN MEMORY ONLY — never a database write, never a new column,
 * never touching {@code paper_positions.stop_loss}. {@code stop_loss} keeps serving the intraday
 * disaster-stop the paper module's 15-second bracket poller reads exactly as it does today, so
 * exit behaviour is BYTE-IDENTICAL before and after this PR — M40 owns the risk calculation only;
 * whether Manas should ever become intraday-trail-managed is a separate, later, owner decision.
 *
 * <p><b>Keyed by paper-position ID, round 4 (cross-vendor review Critical 1, 2026-08-02).</b> An
 * earlier round keyed this cache by the compound {@code (book, exchange, tradingsymbol, side)}
 * tuple — position-IDENTITY-agnostic. The review found this let a stale trail attach to a
 * DIFFERENT economic position sharing the same key: a dead-anchor paper row can be classified
 * "fresh" by the entry pass ({@code SwingBatchEngine#openLotsBySymbol} only sees active signal
 * anchors), and a manual close's eviction can race the daily batch's exit pass (which iterates a
 * snapshot taken before the close) re-writing the SAME tuple key after eviction — either way, a
 * later position on that symbol/side could silently inherit a trail computed for a DIFFERENT
 * position. Keying by the immutable, never-reused {@code paper_positions.id} closes both: a write
 * now resolves the CURRENT open row at write time ({@link PaperEmissionGuard#cacheManasGoverningStop})
 * rather than trusting a symbol tuple, so a closed position's key can never be resurrected, and a
 * brand-new position (a new row, hence a new id) can never read a prior position's entry. An
 * averaging add (same row id, qty/avg change in place) EVICTS its own entry
 * ({@code PaperService#upsertPosition}) rather than silently continuing to trust a trail computed
 * against the pre-average avg/qty — the next exit-pass run recomputes it fresh for the new shape;
 * until then the risk calc conservatively falls back to the persisted {@code stopLoss} (wider,
 * never narrower — the safe direction for a cache miss).
 *
 * <p>Populated once daily by {@code SwingBatchEngine}'s exit pass, via the {@code EmissionGuard}
 * port, on a bar where a held position does NOT exit (it already computes the trail there — see
 * {@code SwingDoctrine#governingStop}). Read by {@link PaperEmissionGuard#effectiveStop} /
 * {@link PaperEmissionGuard#openRiskInr(java.util.List, ManasGoverningStopCache)} and {@code
 * RiskService#manasAggregateRiskWouldCross}, the ONLY consumers.
 *
 * <p>Empty on a fresh boot, or for any position the daily batch has not yet evaluated since the
 * last restart — callers fall back to the persisted (stale) {@code stopLoss} in that gap, the SAME
 * conservative behaviour the aggregate cap already had before this whole M40 effort. Not a
 * regression: a cache miss can only make the risk figure MORE conservative (wider), never less.
 *
 * <p>Never loosens: {@link #put} keeps the tighter of the new value and whatever is already
 * cached for that id, mirroring the "tighten-only" guarantee a persisted ratchet would have
 * enforced at the SQL layer — enforced here in Java since there is no row to constrain the UPDATE
 * against. LONG-only, explicitly: Manas trades long-only (its {@code entry_rules} declare {@code
 * direction: long}), so "tighter" means "higher"; a non-{@code "BUY"} side is rejected outright
 * rather than silently applying a comparison that would be backwards for a short. This is a
 * defensive, currently LATENT guard, not a response to a live row — measured 2026-08-02: zero open
 * SELL rows exist in any book today (an earlier claim citing a "known parked SELL row" was sourced
 * from a stale memory note and was wrong; the cited row, {@code paper_positions} id=28, has been
 * CLOSED since 2026-07-17). The guard stays because Manas is long-only by doctrine, not because a
 * short is reachable today.
 */
@Component
public class ManasGoverningStopCache {

  private final ConcurrentHashMap<Long, BigDecimal> stops = new ConcurrentHashMap<>();

  /**
   * Caches {@code newStop} for the position with this id if it is tighter (higher) than whatever
   * is already cached, or nothing is cached yet. A no-op for any {@code side} other than {@code
   * "BUY"}.
   */
  public void put(long positionId, String side, BigDecimal newStop) {
    if (!"BUY".equals(side) || newStop == null) {
      return;
    }
    stops.merge(positionId, newStop, BigDecimal::max);
  }

  /** The cached governing stop for a position id, or {@code null} if never cached. */
  public BigDecimal get(long positionId) {
    return stops.get(positionId);
  }

  /**
   * Drops a position's cache entry — call on a REAL close (a later re-open mints a new id, so this
   * is memory hygiene, not a correctness requirement once keyed by id) and on an AVERAGING add
   * (the id is retained, but the trail was computed against the pre-average avg/qty and must not
   * silently keep governing the blended position until the next exit-pass run recomputes it).
   */
  public void evict(long positionId) {
    stops.remove(positionId);
  }
}
