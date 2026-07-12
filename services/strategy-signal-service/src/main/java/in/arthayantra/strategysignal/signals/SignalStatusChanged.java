package in.arthayantra.strategysignal.signals;

/**
 * Published after a signal's lifecycle status is mutated in {@link SignalRepository} (the single
 * choke point for {@code transition}/{@code transitionIf}/{@code expireAllActive}) — so EVERY
 * take/dismiss/expire transition, from any caller (the manual {@code /signals/{id}/taken|dismiss}
 * controller, the auto-paper listener, the engine 15:45 sweep, the swing-lot expiry), emits exactly
 * one event without each site having to remember to.
 *
 * <p>App-platform audit 2026-07-10 §7.2.1: the smallest change with the widest UX payoff — before
 * this, a status change left NO transition event, so a browser tab watching the live cockpit went
 * stale. {@link SignalStatusListener} serialises this into the {@code signals.status} Redis frame
 * AFTER_COMMIT (never a phantom frame on a rolled-back mutation), which the gateway bridges to
 * {@code /topic/signals.status}. {@code fromStatus} is null when the caller used the unguarded
 * {@code transition(id, to)} (the prior status is not known without an extra read); it is the
 * guard's {@code from} otherwise.
 */
public record SignalStatusChanged(long signalId, String fromStatus, String toStatus) {}
