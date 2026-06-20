package in.arthayantra.marketdata.bhavcopy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an NSE corporate-action {@code subject} string into a price-adjustment ratio. Pure + total:
 * unrecognised actions (dividends, AGM, buyback, …) yield {@link Optional#empty()} — only splits and
 * bonuses adjust price here.
 *
 * <p>{@code ratio} is the PRE-ex-date multiplier (apply to bars dated before the ex-date):
 *
 * <ul>
 *   <li>SPLIT face value {@code From Rs X To Rs Y} → {@code Y / X} (e.g. 10→2 ⇒ 0.2, price ÷5)
 *   <li>BONUS {@code a:b} (a free shares per b held) → {@code b / (a+b)} (e.g. 1:1 ⇒ 0.5, 1:3 ⇒ 0.75)
 * </ul>
 */
public final class CorporateActionSubjectParser {

  private static final Pattern BONUS =
      Pattern.compile("(?i)\\bbonus\\b[^0-9]*(\\d+)\\s*:\\s*(\\d+)");
  // "...From Rs 10/- ... To Re 1/-" — Rs/Re, optional dot, decimal amounts.
  private static final Pattern SPLIT_FROM_TO =
      Pattern.compile(
          "(?i)from\\s+(?:rs|re)\\.?\\s*([0-9]+(?:\\.[0-9]+)?).*?\\bto\\s+(?:rs|re)\\.?\\s*([0-9]+(?:\\.[0-9]+)?)",
          Pattern.DOTALL);
  private static final Pattern SPLIT_HINT =
      Pattern.compile("(?i)split|sub[- ]?division|face\\s*value");

  private CorporateActionSubjectParser() {}

  /** A recognised split/bonus, with its pre-ex price multiplier. */
  public record Parsed(String kind, BigDecimal ratio) {}

  public static Optional<Parsed> parse(String subject) {
    if (subject == null || subject.isBlank()) {
      return Optional.empty();
    }
    Matcher bonus = BONUS.matcher(subject);
    if (bonus.find()) {
      BigDecimal a = new BigDecimal(bonus.group(1));
      BigDecimal b = new BigDecimal(bonus.group(2));
      BigDecimal denom = a.add(b);
      if (b.signum() > 0 && denom.signum() > 0) {
        return Optional.of(new Parsed("BONUS", b.divide(denom, 8, RoundingMode.HALF_UP)));
      }
    }
    if (SPLIT_HINT.matcher(subject).find()) {
      Matcher split = SPLIT_FROM_TO.matcher(subject);
      if (split.find()) {
        BigDecimal oldFv = new BigDecimal(split.group(1));
        BigDecimal newFv = new BigDecimal(split.group(2));
        if (oldFv.signum() > 0 && newFv.signum() > 0 && oldFv.compareTo(newFv) != 0) {
          return Optional.of(new Parsed("SPLIT", newFv.divide(oldFv, 8, RoundingMode.HALF_UP)));
        }
      }
    }
    return Optional.empty();
  }
}
