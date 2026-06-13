package in.arthayantra.strategyengine.fills;

/**
 * The instrument class a fill is priced under — selects the brokerage form, the statutory fee legs
 * and the no-spec slippage fallback (§D.11; A9 adds {@link #FUTURE} [FP-7]).
 */
public enum InstrumentClass {
  EQUITY,
  OPTION,
  FUTURE
}
