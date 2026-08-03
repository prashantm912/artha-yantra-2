package in.arthayantra.strategysignal.signals;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The paper BOOK a strategy family belongs to ('scalper' · 'minervini' · 'manas-arora' · 'manual' ·
 * 'other'). Defined in the signals module (which the engine + the paper adapter both depend on) so the
 * tag→book mapping is shared WITHOUT the signals module importing paper (the module graph stays
 * acyclic). The book is the strategy's first recognised family tag.
 */
public final class Books {

  public static final String SCALPER = "scalper";
  public static final String MINERVINI = "minervini";
  public static final String MANAS_ARORA = "manas-arora";
  public static final String MANUAL = "manual";
  public static final String OTHER = "other";

  private Books() {}

  /**
   * Every paper book the system routes to — the governor-seeding + reset domain. A book here MUST
   * carry its governor rows (kill_switch + auto_paper_trade) or it trades ungoverned and cannot be
   * killed; the paper module asserts this at boot (audit M16). Includes {@link #MANUAL} (the schema
   * default book, never produced by {@link #fromTags}) so hand-order books are governed too.
   */
  public static List<String> all() {
    return List.of(SCALPER, MINERVINI, MANAS_ARORA, MANUAL, OTHER);
  }

  /**
   * The EOD-MANAGED books: swing families whose holdings are cash equities, exited by the daily swing
   * batch rather than by any intraday path.
   *
   * <p>The one behavioural authority for that fact, so the surfaces that act on it cannot drift apart.
   * Today its sole production consumer is {@code PaperService.isSwingBook} (the signal-freshness
   * exemption), which previously tested the two book constants inline — byte-identical, since
   * {@code BookResolver.MINERVINI}/{@code MANAS_ARORA} ARE these constants. {@code
   * PaperStaleTickAlerter}'s comment has long warned that its {@code artha.paper.eod-managed-books}
   * property must stay in step with that method and nothing enforced it; {@code
   * SwingEquityBracketTripwireIntegrationTest.theTwoEodManagedBookAuthoritiesAgree} now does.
   *
   * <p>Deliberately a COMPILE-TIME set rather than that property: the property suppresses an ALERT,
   * where a blank value costs visibility, and a set that gates behaviour must not be blankable by an
   * operator. A new swing family belongs in both.
   */
  public static Set<String> eodManaged() {
    return Set.of(MINERVINI, MANAS_ARORA);
  }

  /** The book for a family tag set — the first recognised family tag, else {@code OTHER}. */
  public static String fromTags(Collection<String> tags) {
    if (tags == null) {
      return OTHER;
    }
    if (tags.contains(SCALPER)) {
      return SCALPER;
    }
    if (tags.contains(MINERVINI)) {
      return MINERVINI;
    }
    if (tags.contains(MANAS_ARORA)) {
      return MANAS_ARORA;
    }
    return OTHER;
  }
}
