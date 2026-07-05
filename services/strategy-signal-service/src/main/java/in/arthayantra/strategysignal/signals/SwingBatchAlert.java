package in.arthayantra.strategysignal.signals;

/**
 * In-process ops alert from the swing-batch chain (batch schedulers, the graduation evaluator, and
 * the did-not-run canary) — bridged to the ops ntfy topic by the notifier module's
 * {@code SwingBatchAlertListener} (the same notifier←signals event direction as
 * {@code DotInputAlert}; this module cannot import notifier — that is a Modulith cycle).
 *
 * <p>Audit P0-4/H10: the EOD batch chain previously failed (or silently didn't run) with only a
 * log line — missed exit passes meant the pinned stops fired days late at worse prices, silently
 * corrupting the forward-paper evidence.
 */
public record SwingBatchAlert(String batch, String title, String message) {}
