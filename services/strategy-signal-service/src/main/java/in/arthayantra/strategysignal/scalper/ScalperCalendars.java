package in.arthayantra.strategysignal.scalper;

import in.arthayantra.marketcalendar.MarketCalendar;

/**
 * Per-option-root expiry calendar (audit P1-9): NSE weekly index expiry is Tuesday, BSE (SENSEX)
 * is Thursday — one NSE-Tuesday calendar for all roots armed SENSEX hero-zero on NIFTY's expiry
 * day and skipped it on the real BSE Thursday, and monthly chain-OI suppression fired on the wrong
 * day for SENSEX reads. Memoized: the gate resolves per evaluation and {@code bse()}/{@code nse()}
 * re-read the holiday CSV on every construction.
 */
final class ScalperCalendars {

  private static final MarketCalendar NSE = MarketCalendar.nse();
  private static final MarketCalendar BSE = MarketCalendar.bse();

  private ScalperCalendars() {}

  /** BSE calendar for SENSEX-rooted reads; NSE for everything else. */
  static MarketCalendar forUnderlying(String underlying) {
    return underlying != null && underlying.toUpperCase(java.util.Locale.ROOT).contains("SENSEX")
        ? BSE
        : NSE;
  }
}
