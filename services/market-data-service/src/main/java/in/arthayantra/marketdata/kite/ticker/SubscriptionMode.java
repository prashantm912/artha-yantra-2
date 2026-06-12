package in.arthayantra.marketdata.kite.ticker;

/**
 * The Kite tick-mode ladder (B-6): {@code ltp} (8 B) &lt; {@code quote} (44 B) &lt; {@code full}
 * (184 B); index instruments use the 28 B index-quote / 32 B index-full layouts instead — the
 * sizes the B-9 frame guard checks per token.
 */
public enum SubscriptionMode {
  LTP("ltp"),
  QUOTE("quote"),
  FULL("full");

  private final String wireName;

  SubscriptionMode(String wireName) {
    this.wireName = wireName;
  }

  /** The SDK/wire mode string. */
  public String wireName() {
    return wireName;
  }

  /** Expected packet bytes for this mode (B-9 — the {8, 28, 32, 44, 184} set). */
  public int expectedBytes(boolean isIndex) {
    return switch (this) {
      case LTP -> 8;
      case QUOTE -> isIndex ? 28 : 44;
      case FULL -> isIndex ? 32 : 184;
    };
  }

  /** The higher of two modes (highest-requested-mode resolution). */
  public static SubscriptionMode max(SubscriptionMode a, SubscriptionMode b) {
    return a.compareTo(b) >= 0 ? a : b;
  }

  /** Parses the wire name; null on unknown. */
  public static SubscriptionMode fromWireName(String name) {
    for (SubscriptionMode mode : values()) {
      if (mode.wireName.equalsIgnoreCase(name)) {
        return mode;
      }
    }
    return null;
  }
}
