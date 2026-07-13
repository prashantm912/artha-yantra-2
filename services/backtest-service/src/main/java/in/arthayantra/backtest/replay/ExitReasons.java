package in.arthayantra.backtest.replay;

import java.util.Locale;

/**
 * Canonical casing for {@code backtest_trades.exit_reason} (chip task_cf5d58c8). Every replay path
 * that persists a trade — candle-path ({@link ReplayEngine}), options-premium ({@code
 * OptionsPremiumReplay}), and deep-swing ({@code DeepSwingService}) — routes through {@link
 * TradeRepository#insertAll}, which passes the reason through {@link #canonical} so the shared table
 * never mixes casing.
 *
 * <p>The canonical vocabulary is <strong>UPPERCASE</strong> (STOP_LOSS / TRAILING_STOP / TAKE_PROFIT
 * / SQUARE_OFF / TIME_STOP / SIGNAL_EXIT / END_OF_DATA / EXPIRY_SETTLEMENT / FAST_MOVE / PARABOLIC),
 * chosen to match the live paper {@code close_reason} (PaperBracketEvaluator — STOP_LOSS/TAKE_PROFIT)
 * that the optimizer's exit-reason reconciliation compares a backtest against: aligning the two
 * casings is what keeps that metric from reporting divergence that is purely cosmetic. The
 * candle-path engine computes lowercase engine-native reasons ({@code ExitDecision.type} — stop_loss,
 * …); {@link #canonical} upper-cases them at the persistence boundary. Idempotent for the paths that
 * already emit UPPERCASE.
 *
 * <p>This is <strong>casing-only</strong>: it deliberately does NOT reconcile vocabulary/granularity
 * (the options path's FAST_MOVE / PARABOLIC stay distinct from the candle path's coarser SQUARE_OFF).
 */
public final class ExitReasons {

  private ExitReasons() {}

  /**
   * The canonical (UPPERCASE) persisted form of an exit reason; {@code null} passes through so an
   * open-at-end trade with no reason is unaffected. {@link Locale#ROOT} avoids the Turkish-i locale
   * hazard.
   */
  public static String canonical(String reason) {
    return reason == null ? null : reason.toUpperCase(Locale.ROOT);
  }
}
