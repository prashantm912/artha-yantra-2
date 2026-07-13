package in.arthayantra.marketdata.bhavcopy;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses dividend subjects into per-share rupee cash amounts without inferring face value. */
public final class DividendSubjectParser {

  private static final Pattern DIVIDEND = Pattern.compile("(?i)\\bdividend\\b");
  private static final Pattern RUPEE_AMOUNT =
      Pattern.compile("(?i)\\b(?:rs|re)\\.?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:/-)?");

  private DividendSubjectParser() {}

  /** True only when the subject explicitly names a dividend. */
  public static boolean isDividend(String subject) {
    return subject != null && DIVIDEND.matcher(subject).find();
  }

  /** The first {@code Rs|Re} amount in a dividend subject, or empty when none is stated. */
  public static Optional<BigDecimal> parseAmount(String subject) {
    if (!isDividend(subject)) {
      return Optional.empty();
    }
    Matcher amount = RUPEE_AMOUNT.matcher(subject);
    return amount.find() ? Optional.of(new BigDecimal(amount.group(1))) : Optional.empty();
  }
}
