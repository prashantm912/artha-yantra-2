package in.arthayantra.strategysignal.scalper;

/**
 * How {@link ConnectTheDotsScorer} treats a dot whose INPUT is unavailable (F5 plan unit U4b).
 *
 * <p><b>The inconsistency this exists to close.</b> Three different missing-input rules grew up side
 * by side in the scorer, and nothing reconciled them — a data gap could help, hurt, or vanish
 * depending only on which dot happened to be missing:
 *
 * <ul>
 *   <li><b>withheld</b> — {@code iv_rank} alone: a null input is out of BOTH the numerator and the
 *       denominator (the P3 {@code absent} path), so the gap is not evidence in either direction.
 *   <li><b>supports</b> — {@code vix}, {@code basis}, {@code premium_skew}, {@code dow}: a null
 *       input scores as a PASSING dot, so a dead feed silently props the composite up.
 *   <li><b>opposes-in-denominator</b> — every other ENABLED dot, including the {@link
 *       OiQuadrant#NEUTRAL} "snapshot unavailable" sentinel: a null input scores {@code
 *       supports=false} while still counting its full weight in the denominator, so the gap is
 *       scored as evidence AGAINST the side. Fifteen of the default eighteen dots, and seventeen
 *       once the {@code iv-per-strike} tag adds {@code iv_slope} + {@code iv_abs_band} (both also
 *       opponent-on-missing) — so this class GROWS with the enabled dot set, it is not a fixed list.
 * </ul>
 *
 * <p>{@link #WITHHELD} unifies all three to the first rule. It is DEFAULT-OFF behind the
 * {@code dot-null-withheld} strategy tag: unifying the rule CHANGES which signals fire (it is,
 * mechanically, a loosening for the third class), and every measured loosening of the scalper entry
 * gate so far has lost money — so the change ships inert and earns its evidence through the
 * {@code dot-null-withheld} shadow variant before anyone arms it.
 */
public enum NullPolicy {

  /** Today's behaviour: each dot keeps its own hand-rolled missing-input rule (the three above). */
  LEGACY,

  /** F5 U4b: ONE rule — an input-missing dot is withheld from BOTH the numerator and denominator. */
  WITHHELD;

  /** True when an input-missing dot must be withheld rather than scored. */
  public boolean withholds() {
    return this == WITHHELD;
  }
}
