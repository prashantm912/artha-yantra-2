package in.arthayantra.strategysignal.signals;

import java.util.Map;

/**
 * Admits — or refuses — the ORDER INTENT a take would create, BEFORE the {@code ACTIVE→TAKEN} CAS
 * commits. A port declared HERE and implemented in {@code paper} so the module graph stays acyclic:
 * {@code signals} never imports {@code paper} (Modulith), and the instrument master the verdict
 * needs lives on the paper side.
 *
 * <p><b>Why the order matters.</b> Both {@code SignalTaken} publishers used to commit the transition
 * FIRST and discover the refusal afterwards. {@code @EventListener} is synchronous, so the refusal
 * happens inside the very same call stack — but one statement too late to veto anything: {@code
 * PaperService#openOrder} throws its {@code DATA_GAP} / {@code VALIDATION_FAILED} refusal, {@code
 * PaperSignalListener} catches and logs it, and {@code LiveOrderService} logs and returns. The
 * signal is left {@code TAKEN} with no order, no position and no rejection row, and that residue is
 * PERMANENT: {@code TakenSignalResolver} only ever fires on a {@code PaperPositionClosed} event, so
 * an anchor that never had a position never receives one, while {@link
 * SignalRepository#activeEntry} counts {@code ACTIVE} and {@code TAKEN} alike and therefore
 * suppresses re-entry on that instrument for that published version forever.
 *
 * <p>A refusal leaves the signal {@code ACTIVE} — recoverable by construction, and the direction
 * this repo already settled for entries: "entries need fresh truth (you can always NOT enter)".
 *
 * <p>This is a GATE, not a replacement. The writer-side revalidation in {@code PaperService} and
 * {@code LiveOrderService} stays: they are the layers that actually place the order, they can be
 * reached without passing through a publisher (swing effect repairs, hand tickets, retries), and the
 * instrument master can move between this check and the fill.
 */
@FunctionalInterface
public interface TakeAdmission {

  /**
   * The verdict on one take. {@code code} / {@code reason} / {@code details} are populated on a
   * refusal only, and deliberately carry the SAME error codes the writers throw, so one fact reads
   * as one fact wherever it surfaces.
   */
  record Verdict(boolean admitted, String code, String reason, Map<String, Object> details) {

    /** The pass — nothing to report. */
    public static final Verdict ADMITTED = new Verdict(true, null, null, Map.of());

    /** A refusal carrying the writer's own error code plus the facts that decided it. */
    public static Verdict refused(String code, String reason, Map<String, Object> details) {
      return new Verdict(false, code, reason, details);
    }
  }

  /**
   * Whether a take of {@code signalId} for {@code qty} may commit the {@code ACTIVE→TAKEN}
   * transition.
   *
   * @param qty the quantity the take would carry; {@code null} or non-positive means the take
   *     creates NO order intent at all (a bare manual "I filled this at the broker myself" anchor),
   *     which every writer already no-ops on and which this port therefore admits unchanged
   */
  Verdict admit(long signalId, Integer qty);
}
