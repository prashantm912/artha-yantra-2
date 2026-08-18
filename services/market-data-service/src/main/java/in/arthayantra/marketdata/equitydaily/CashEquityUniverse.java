package in.arthayantra.marketdata.equitydaily;

/**
 * The ONE definition of the cash-equity series predicate: {@code series IN ('EQ','BE')}.
 *
 * <p>{@code marketdata.nse_eod_bhavcopy} is MULTI-SERIES (13 series exist: EQ, BE, BZ, E1, GB, GS,
 * IV, MF, N1, RR, SM, ST, SZ), but the house cash-equity universe — the population the swing
 * screeners rank and the paper books actually trade — is {@code EQ} plus {@code BE}. That is the
 * standard already encoded at {@code BhavcopyBackfillService:123}
 * ({@code artha.nse.bhavcopy.candle-series:EQ,BE}), {@code AdjustedEquityDailySql:76,207,259},
 * {@code TrendTemplateService:81} and {@code ManasScreenService:84}.
 *
 * <p>An {@code EQ}-only filter therefore drops BE names silently, and it fails in the ALARMING
 * direction — a missing row reads as an outage rather than as a filter artifact. Measured
 * 2026-08-17 on the latest session: EQ 2463 / BE 250 symbols, so EQ-only loses 9.2% of the
 * EQ+BE universe; across the {@code minervini} + {@code manas-arora} paper books 7 of the 32
 * distinct symbols ever traded are BE-series. See ledger row H24
 * ({@code series-eq-filter-call-sites-never-swept}) in
 * {@code docs/superpowers/plans/2026-07-02-remaining-items.md} for the full 30-site sweep.
 *
 * <h2>⚠️ Sites that must NOT adopt this constant</h2>
 *
 * The danger this class creates is a future well-meaning consolidation. H24's sweep classified
 * every site individually and 11 of 30 are CORRECT AS EQ-ONLY. Do not blanket-replace.
 *
 * <ul>
 *   <li><b>{@code BhavcopyCloseCanary:190,581,612}</b> — EQ-only is DELIBERATE and was measured,
 *       not assumed; its own comment at {@code :169-178} records that widening the population
 *       buys exactly zero rows today and explains why the narrowing is left standing.
 *   <li><b>{@code PreOpenEquityScanService:181,195}</b> — the population is F&amp;O underlyings,
 *       which are EQ by exchange eligibility. Widening it would admit names that cannot be in it.
 *   <li><b>The six {@code max(trade_date)} pins</b> at {@code EquityIndexContributionService:233,250},
 *       {@code EquitySectorService:176,197} and {@code EquityReturnsService:105,141}. These are
 *       correct <i>only because</i> their paired populations ({@code :232}, {@code :175},
 *       {@code :87}) are themselves EQ-only. <b>Widening a population without widening its pin
 *       produces a stale close under a current badge</b> — the precise bug those pins exist to
 *       prevent, reasoned at {@code TrendTemplateService:149-158}. Pin and population must move
 *       together or not at all, so the fix order here is not free.
 * </ul>
 *
 * <p>⚠️ This module is a LEAF — zero outgoing {@code in.arthayantra.marketdata} imports (see
 * {@code package-info}). That is what makes this constant safe for any module to depend on, and it
 * is why the constant lives here rather than beside its first caller.
 */
public final class CashEquityUniverse {

  private CashEquityUniverse() {}

  /**
   * The cash-equity series predicate, for an UNALIASED {@code nse_eod_bhavcopy} reference.
   *
   * <p>For an ALIASED reference use {@link #qualified(String)}.
   */
  public static final String SERIES_PREDICATE = "series IN ('EQ','BE')";

  /**
   * The same predicate against an ALIASED {@code nse_eod_bhavcopy} reference — {@code
   * qualified("b")} yields {@code b.series IN ('EQ','BE')}.
   *
   * <p>Added when the first real aliased call sites adopted this, which is the condition the
   * previous javadoc set for adding it: {@code AdjustedEquityDailySql:259},
   * {@code SymbolLineageDetector:170} and {@code PlaneDivergenceProbe:143} all join bhavcopy as
   * {@code b} and had their own copy of the literal.
   */
  public static String qualified(String alias) {
    return alias + "." + SERIES_PREDICATE;
  }
}
