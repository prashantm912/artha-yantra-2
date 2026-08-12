package in.arthayantra.marketdata.bhavcopy;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises an NSE/BSE corporate-action {@code subject} as a company NAME CHANGE, and pulls the
 * old/new company names out of it when the feed spells them.
 *
 * <p>Pure + total, and a sibling of {@link DividendSubjectParser}: it is consulted only after {@link
 * CorporateActionSubjectParser} has declined the subject, i.e. on the branch that used to
 * {@code continue} past everything that is not a price adjustment.
 *
 * <p><b>What this does NOT give you.</b> The feed names the COMPANY ("… From Gujarat Gas Limited To
 * Gujarat Energy Limited"), never the predecessor TICKER, and the row is keyed by the symbol as of
 * the ex-date. So a captured event is corroborating evidence for a pair the price-continuity rule
 * found — and an audit trail for renames that rule is structurally blind to — but it is not a
 * predecessor→successor pair on its own.
 */
public final class NameChangeSubjectParser {

  // "Change in Name", "Change Of Name", "Change in Company Name", "Name Change".
  private static final Pattern NAME_CHANGE =
      Pattern.compile("(?i)(?:change\\s+(?:in|of)\\s+(?:the\\s+)?(?:company\\s+)?name|\\bname\\s+change\\b)");

  // "... From <old> To <new>" — non-greedy old, so the FIRST " to " separates the two names.
  private static final Pattern FROM_TO =
      Pattern.compile("(?i)\\bfrom\\s+(.+?)\\s+to\\s+(.+?)\\s*$", Pattern.DOTALL);

  private NameChangeSubjectParser() {}

  /** The old and new company names as the feed spelled them. */
  public record Names(String fromName, String toName) {}

  /** Whether {@code subject} announces a company name change. */
  public static boolean isNameChange(String subject) {
    return subject != null && !subject.isBlank() && NAME_CHANGE.matcher(subject).find();
  }

  /**
   * The old/new company names, when the subject spells them. Empty when it only announces that a
   * name changed (NSE does emit a bare "Change In Name"), which is still worth recording — the
   * ex-date and ISIN are the useful parts.
   */
  public static Optional<Names> parseNames(String subject) {
    if (!isNameChange(subject)) {
      return Optional.empty();
    }
    Matcher m = FROM_TO.matcher(subject);
    if (!m.find()) {
      return Optional.empty();
    }
    String from = m.group(1).trim();
    String to = m.group(2).trim();
    return from.isEmpty() || to.isEmpty() ? Optional.empty() : Optional.of(new Names(from, to));
  }
}
