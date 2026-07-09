package in.arthayantra.strategyengine.fills;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Drift canary for the statutory-fee schedule (register §9-14). {@link FeeConstants} is a hand-pinned
 * snapshot of the Zerodha brokerage + NSE/SEBI/CBDT/state-stamp rates as of 2026-06-13, applied
 * identically in backtest and the paper ledger — so a SILENT edit (or an un-refreshed stale rate after
 * a statutory change) biases every backtest and paper-P&amp;L number with no test to notice.
 *
 * <p>This pins each constant to its frozen value. If a rate genuinely changes, this test SHOULD fail —
 * do NOT just make it pass:
 * <ol>
 *   <li>verify the new rate against the current NSE / SEBI / CBDT / Zerodha / state-stamp schedule,
 *   <li>update {@link FeeConstants} <b>and</b> the expected value here,
 *   <li>bump the "pinned at ... 2026-06-13" date in the {@link FeeConstants} javadoc + the decisions log.
 * </ol>
 * The failure message is the whole point: it converts an invisible rate drift into a conscious,
 * reviewed refresh.
 */
class FeeConstantsDriftTest {

  private static final String WHY =
      "FeeConstants changed vs the pinned 2026-06-13 schedule — a statutory/brokerage rate drift biases "
          + "every backtest + paper P&L. Verify vs the current NSE/SEBI/CBDT/Zerodha/stamp schedule, then "
          + "update FeeConstants + this expected value + the pin date (register §9-14). Do NOT just pass it.";

  @Test
  void brokerageAndStampAndSebiMatchTheFrozenSchedule() {
    assertThat(FeeConstants.FLAT_BROKERAGE_INR).as(WHY).isEqualByComparingTo("20.00");
    assertThat(FeeConstants.STAMP_OPTION).as(WHY).isEqualByComparingTo("0.00003000");
    assertThat(FeeConstants.STAMP_FUTURE).as(WHY).isEqualByComparingTo("0.00002000");
    assertThat(FeeConstants.STAMP_EQUITY).as(WHY).isEqualByComparingTo("0.00001500");
    assertThat(FeeConstants.SEBI).as(WHY).isEqualByComparingTo("0.00000100");
    assertThat(FeeConstants.GST).as(WHY).isEqualByComparingTo("0.18");
  }

  @Test
  void sttAndCttRatesMatchTheFrozenSchedule() {
    assertThat(FeeConstants.STT_OPTION_SELL).as(WHY).isEqualByComparingTo("0.001000");
    assertThat(FeeConstants.STT_FUTURE_SELL).as(WHY).isEqualByComparingTo("0.000200");
    assertThat(FeeConstants.STT_EQUITY).as(WHY).isEqualByComparingTo("0.001000");
    assertThat(FeeConstants.STT_OPTION_EXERCISE).as(WHY).isEqualByComparingTo("0.001250");
  }

  @Test
  void exchangeTransactionChargesMatchTheFrozenSchedule() {
    assertThat(FeeConstants.TXN_OPTION).as(WHY).isEqualByComparingTo("0.00035030");
    assertThat(FeeConstants.TXN_FUTURE).as(WHY).isEqualByComparingTo("0.00001730");
    assertThat(FeeConstants.TXN_EQUITY).as(WHY).isEqualByComparingTo("0.00002970");
  }

  @Test
  void slippageFallbacksMatchTheFrozenSchedule() {
    assertThat(FeeConstants.EQUITY_FALLBACK_BPS).as(WHY).isEqualByComparingTo("5");
    assertThat(FeeConstants.FUTURE_FALLBACK_TICKS).as(WHY).isEqualTo(1);
    assertThat(FeeConstants.OPTION_FALLBACK_TICKS).as(WHY).isEqualTo(1);
  }
}
