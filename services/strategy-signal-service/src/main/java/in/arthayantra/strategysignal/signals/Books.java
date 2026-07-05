package in.arthayantra.strategysignal.signals;

import java.util.Collection;

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
