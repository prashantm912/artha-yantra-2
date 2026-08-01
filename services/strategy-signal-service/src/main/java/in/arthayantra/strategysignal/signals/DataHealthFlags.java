package in.arthayantra.strategysignal.signals;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategysignal.scalper.OiQuadrant;
import in.arthayantra.strategysignal.scalper.ScalperGateContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-row gate-input health, computed at record time (signal-analysis README §7 row 4, F5 unit U3).
 *
 * <p>PURE: a total function of {@code (context, sessionDate)}. No clock, no I/O, no Spring — so it is
 * exhaustively testable and costs nothing to run on the {@link RejectionWriter}'s bounded async
 * thread. It reads the SAME {@link ScalperGateContext} the diagnostic JSON is serialized from, so the
 * flags can never disagree with the row they annotate.
 *
 * <p><b>A flag means the input was ABSENT when the gate scored this bar.</b> It never means "the
 * input had an unremarkable value". That line is where a naive port of {@link DotHealthCanary}'s
 * probes goes wrong, and the difference is not cosmetic: the canary asks aggregate questions over a
 * 40-row window ("did {@code spurtPricePct} move at all today", "was the window all-NEUTRAL"), whose
 * per-row analogues are ordinary market states. Measured on live rows 2026-07-20..31,
 * {@code spurtPricePct = 0} holds on ~43% of context-bearing rows across EVERY session, expiry or
 * not — a flat bar, not a defect. Only unambiguous absence is flagged here.
 *
 * <p>Rows blocked before the chain fetch carry no context at all (~23% of live rows: the
 * time-window / time-of-day / option-side rails). Those are UNINFORMATIVE, not degraded —
 * {@code contextBearing=false}, no flags, not degraded. T17's lesson (a context-less row cannot
 * testify about dot liveness) applied per row instead of per window.
 *
 * <p><b>The S24 per-root exemption.</b> On a MONTHLY index expiry {@code MarketOiClient.oi()} skips
 * the whole OI block by design — the expiring series' writers are unwinding, so chain OI is corrupt —
 * and returns an inert {@code Oi}: NEUTRAL on both quadrants, null on every soft numeric
 * (MarketOiClient:347-356). Every OI dot is then legitimately non-confirming, so {@link #OI_INERT}
 * is withheld and {@code oiSuppressed} records why. ⚠️ The suppression is keyed PER OI ROOT, NOT per
 * date: NSE's monthly index expiry is the last Tuesday, BSE's (SENSEX) the last Thursday, and
 * {@code MarketOiClient} keys on the row's own underlying. On an NSE-only expiry day a SENSEX-rooted
 * OI read is NOT suppressed and a dead OI block there IS a genuine outage — a date-keyed exemption
 * would silence exactly that. This is the misreading that cost a live investigation (#1073) and it
 * was made and corrected once already inside {@code DotHealthCanary} (2026-07-28). Measured
 * confirmation that the signature is exact: across 2026-07-20..31 the inert signature fired on
 * 1,068/1,068 rows on 2026-07-28 (the NSE monthly expiry) and on 0 rows on all nine other sessions.
 *
 * @param degraded true iff {@link #flags()} is non-empty — at least one input was absent
 * @param contextBearing false when the block landed before the chain fetch (no context to judge)
 * @param oiSuppressed true when this row's OI root is on ITS monthly index expiry (S24, by design)
 * @param flags the absent inputs, in a stable declaration order
 */
public record DataHealthFlags(
    boolean degraded, boolean contextBearing, boolean oiSuppressed, List<String> flags) {

  /** Breadth read failed: a real session always has at least one advance or decline. */
  public static final String BREADTH_ABSENT = "breadth-absent";

  /** No ATM IV on the chain read. */
  public static final String ATM_IV_ABSENT = "atm-iv-absent";

  /** No IV rank — the /iv-history daily series was short or unavailable. */
  public static final String IV_RANK_ABSENT = "iv-rank-absent";

  /** No India VIX level. */
  public static final String VIX_ABSENT = "vix-absent";

  /** No FII long %. */
  public static final String FII_ABSENT = "fii-absent";

  /** No Dow direction — the global cue was unconfigured, off-hours or history. */
  public static final String DOW_ABSENT = "dow-absent";

  /**
   * The OI half contributed nothing: both quadrants NEUTRAL and every soft numeric null (or no
   * {@code Oi} at all). Withheld when {@code oiSuppressed} — see the class javadoc.
   */
  public static final String OI_INERT = "oi-inert";

  /**
   * The per-root expiry calendars. Memoized as statics because {@code nse()}/{@code bse()} re-read
   * the bundled holiday CSV on every construction.
   *
   * <p>This restates {@code ScalperCalendars.forUnderlying}'s one-line root rule rather than calling
   * it: that class is package-private in {@code scalper}. {@link DotHealthCanary} (:598-615) made
   * the same call for the same reason, and this mirrors it so the two health surfaces cannot
   * disagree about which calendar a root uses.
   */
  private static final MarketCalendar NSE = MarketCalendar.nse();

  private static final MarketCalendar BSE = MarketCalendar.bse();

  /** The verdict for a row that never reached the chain fetch. */
  private static final DataHealthFlags NO_CONTEXT =
      new DataHealthFlags(false, false, false, List.of());

  /**
   * Judges one bar's gate inputs.
   *
   * @param context the gate context the diagnostic was built from; null when the block landed before
   *     the chain fetch
   * @param sessionDate the bar's own IST date — never {@code now()}, which straddles IST midnight
   *     against a bar recorded either side of it
   */
  public static DataHealthFlags of(ScalperGateContext context, LocalDate sessionDate) {
    if (context == null) {
      return NO_CONTEXT;
    }
    boolean oiSuppressed = oiSuppressed(context.underlying(), sessionDate);
    List<String> flags = new ArrayList<>(7);

    ScalperGateContext.Macro macro = context.macro();
    // A null Macro means every macro input is absent; flagging each individually needs no special
    // case and the resulting row says exactly that.
    if (macro == null || macro.advances() + macro.declines() == 0) {
      flags.add(BREADTH_ABSENT);
    }
    if (macro == null || macro.atmIv() == null) {
      flags.add(ATM_IV_ABSENT);
    }
    if (macro == null || macro.ivRank() == null) {
      flags.add(IV_RANK_ABSENT);
    }
    if (macro == null || macro.vixLevel() == null) {
      flags.add(VIX_ABSENT);
    }
    if (macro == null || macro.fiiLongPct() == null) {
      flags.add(FII_ABSENT);
    }
    if (macro == null || macro.dowUp() == null) {
      flags.add(DOW_ABSENT);
    }
    if (!oiSuppressed && oiInert(context.oi())) {
      flags.add(OI_INERT);
    }
    return new DataHealthFlags(!flags.isEmpty(), true, oiSuppressed, List.copyOf(flags));
  }

  /**
   * Is THIS row's OI root on its own monthly index expiry? Mirrors
   * {@code ScalperCalendars.forUnderlying} — BSE (Thursday monthly) for a SENSEX-rooted read, NSE
   * (Tuesday monthly) for everything else. A row with no underlying falls to NSE, so a dead OI block
   * on a BSE-only expiry day still reads degraded: fail-loud is the right default for a health flag.
   */
  private static boolean oiSuppressed(String underlying, LocalDate sessionDate) {
    if (sessionDate == null) {
      return false;
    }
    MarketCalendar rootCalendar =
        underlying != null && underlying.toUpperCase(Locale.ROOT).contains("SENSEX") ? BSE : NSE;
    try {
      return rootCalendar.isMonthlyIndexExpiryDay(sessionDate);
    } catch (IllegalArgumentException uncoveredYear) {
      return false; // the calendar cliff has its own canary; never claim by-design on a guess
    }
  }

  /**
   * The inert-OI signature: the exact shape {@code MarketOiClient.oi()} returns when it skips the
   * block (NEUTRAL quadrants + null soft numerics), which is also what a total OI-read failure
   * leaves behind. Both quadrants AND all four magnitudes must be empty — on a live read even a flat
   * tape carries a {@code sentimentPct} and spurt magnitudes, so a genuine quiet bar never matches.
   * {@code futuresBasis} is excluded on purpose: it is price-derived and survives the S24 skip.
   */
  private static boolean oiInert(ScalperGateContext.Oi oi) {
    if (oi == null) {
      return true;
    }
    return quadrantInert(oi.futures())
        && quadrantInert(oi.underlying())
        && oi.sentimentPct() == null
        && oi.trendingPeMinusCePct() == null
        && oi.spurtOiPct() == null
        && oi.spurtPricePct() == null;
  }

  private static boolean quadrantInert(OiQuadrant quadrant) {
    return quadrant == null || quadrant == OiQuadrant.NEUTRAL;
  }
}
