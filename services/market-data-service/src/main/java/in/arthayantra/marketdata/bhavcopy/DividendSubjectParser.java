package in.arthayantra.marketdata.bhavcopy;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses dividend subjects into per-share rupee cash amounts without inferring face value.
 *
 * <p>A subject can state more than one {@code Rs|Re} amount, and the two shapes mean opposite
 * things — so the amounts are only added when they are genuinely independent cash components:
 *
 * <ul>
 *   <li><b>Additive</b> — {@code "Dividend - Rs 10 Per Share/Special Dividend - Rs 30 Per Share"}:
 *       two separate payouts, total 40. Taking only the first under-reported these.
 *   <li><b>Itemised</b> — {@code "Distribution - Rs 5.25 Per Unit Consisting Of Interest - Rs 1.85/
 *       … / Dividend - Re 0.83 …"}: the InvIT/REIT shape, where the LEADING amount is the stated
 *       total and everything after it is a component OF that total. Adding these double-counts
 *       (exactly 2× the truth), so the leading total is taken as-is.
 * </ul>
 *
 * <p>The two are told apart by a breakdown marker ({@code consist…}/{@code compris…}). The match is
 * a deliberately loose substring rather than a word-boundary one because the feed emits run-together
 * text such as {@code "Consistingrs 1.1113"} — and because the failure directions are asymmetric: a
 * false positive merely keeps the pre-existing leading-amount behaviour, while a false negative
 * doubles a number.
 *
 * <p>Known limitation, unchanged here: for the itemised shape the value is the whole distribution,
 * not the dividend component alone.
 *
 * <p><b>BSE dash shape.</b> BSE renders the cash amount as a separate feed field, so its purpose
 * text reads {@code "Final Dividend - Rs. - 10.0000"} — a dash sits between the rupee marker and the
 * number, which {@link #RUPEE_AMOUNT} cannot match. Every BSE row ever captured (2,574 of 2,574,
 * live {@code artha} 2026-08-03) therefore stored a NULL amount. {@link #DASH_AMOUNT} is tried only
 * as a <em>fallback</em>, when the primary pattern matched nothing at all — so no subject that
 * already parses can change value, and NSE behaviour is unreachable-by-construction unchanged.
 *
 * <p>The fallback accepts only an <em>unambiguous single</em> dash amount. All 2,574 live BSE rows
 * state exactly one, so a second one is a shape we have never seen and cannot classify as additive
 * or itemised; refusing leaves the amount NULL. That is the safe failure direction — a NULL is a
 * visibly absent number, while a wrong number is indistinguishable from a right one.
 */
public final class DividendSubjectParser {

  private static final Pattern DIVIDEND = Pattern.compile("(?i)\\bdividend\\b");
  private static final Pattern RUPEE_AMOUNT =
      Pattern.compile("(?i)\\b(?:rs|re)\\.?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:/-)?");
  private static final Pattern DASH_AMOUNT =
      Pattern.compile("(?i)\\b(?:rs|re)\\.?\\s*-\\s*([0-9]+(?:\\.[0-9]+)?)");
  private static final Pattern BREAKDOWN = Pattern.compile("(?i)consist|compris");

  private DividendSubjectParser() {}

  /** True only when the subject explicitly names a dividend. */
  public static boolean isDividend(String subject) {
    return subject != null && DIVIDEND.matcher(subject).find();
  }

  /**
   * The rupee cash amount of a dividend subject: the sum of every {@code Rs|Re} amount for an
   * additive subject, or the leading stated total for an itemised one. Falls back to the BSE
   * {@code "Rs. - 10.0000"} dash shape when no {@code Rs|Re}-adjacent amount is stated at all.
   * Empty when none is stated, or when the dash shape states more than one.
   */
  public static Optional<BigDecimal> parseAmount(String subject) {
    if (!isDividend(subject)) {
      return Optional.empty();
    }
    Matcher amount = RUPEE_AMOUNT.matcher(subject);
    if (!amount.find()) {
      return parseDashAmount(subject);
    }
    BigDecimal total = new BigDecimal(amount.group(1));
    if (BREAKDOWN.matcher(subject).find()) {
      return Optional.of(total); // leading amount is the stated total; the rest itemise it
    }
    while (amount.find()) {
      total = total.add(new BigDecimal(amount.group(1)));
    }
    return Optional.of(total);
  }

  /** The BSE {@code "Rs. - 10.0000"} shape, accepted only when the subject states exactly one. */
  private static Optional<BigDecimal> parseDashAmount(String subject) {
    Matcher dash = DASH_AMOUNT.matcher(subject);
    if (!dash.find()) {
      return Optional.empty();
    }
    BigDecimal single = new BigDecimal(dash.group(1));
    if (dash.find()) {
      return Optional.empty(); // unclassifiable: additive or itemised? leave the amount NULL
    }
    return Optional.of(single);
  }
}
