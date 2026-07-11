package in.arthayantra.strategysignal.signals;

import java.util.Collection;
import java.util.List;

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
