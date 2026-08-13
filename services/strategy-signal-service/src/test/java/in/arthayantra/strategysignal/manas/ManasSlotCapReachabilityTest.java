package in.arthayantra.strategysignal.manas;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The manas-arora book's declared slot cap must be REACHABLE against its portfolio open-risk cap.
 *
 * <p><b>The defect (measured live 2026-08-13).</b> Both manas YAMLs sized at {@code risk_pct_equity:
 * 1.0} while {@code artha.manas-arora.pyramid.max-portfolio-risk-pct} is {@code 6.0} and
 * {@code max_open_paper_positions} (V021) is {@code 7}. 1.0 × 6 = 6.0 = the cap EXACTLY, so the sixth
 * position consumed the entire budget and the seventh slot could never be filled — not as a tuning
 * accident but by arithmetic. Live the book sat at ₹8,569.23 of open risk against a ₹8,576.78 ceiling
 * — 99.91% of cap, ₹7.55 of headroom — and refused a fresh entry on every business day from 08-03
 * (KAPSTON, E2E, KABRAEXTRU, INDOTECH, MTARTECH, CONFIPET, PANACHE, BIRLACABLE).
 *
 * <p>These read the SHIPPED YAML rather than restating a constant, so a revert to 1.0 — or a future
 * edit to only ONE of the two files, which share a single {@code Books.MANAS_ARORA} risk budget —
 * reddens here.
 */
class ManasSlotCapReachabilityTest {

  private static final List<String> STRATEGIES = List.of("manas-arora-breakout", "manas-arora-vcp");

  /** V021__paper_books.sql: {@code ('manas-arora', 'max_open_paper_positions', {"value": 7})}. */
  private static final int DECLARED_SLOTS = 7;

  /**
   * {@code ManasPyramidPolicy}'s {@code @Value} default AND the deployed value — docker-compose.yml
   * {@code ARTHA_MANAS_ARORA_PYRAMID_MAX_PORTFOLIO_RISK_PCT:-6.0}, confirmed against
   * {@code docker inspect ay-strategy-signal-service} on 2026-08-13.
   */
  private static final BigDecimal CAP_PCT = new BigDecimal("6.0");

  private static BigDecimal riskPctEquity(String id) throws IOException {
    try (InputStream in =
        ManasSlotCapReachabilityTest.class.getResourceAsStream(
            "/manas-arora-strategies/" + id + ".yaml")) {
      assertThat(in).as("classpath resource for " + id).isNotNull();
      var config =
          StrategyDocuments.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).config();
      var sizing = config.path("risk").path("position_sizing");
      assertThat(sizing.path("method").asText()).as(id + " sizes by atr_risk").isEqualTo("atr_risk");
      var pct = sizing.path("params").path("risk_pct_equity");
      assertThat(pct.isMissingNode()).as(id + " declares risk_pct_equity").isFalse();
      return new BigDecimal(pct.asText());
    }
  }

  @Test
  void bothManasSetupsSizeIdenticallyBecauseTheyShareOneRiskBudget() throws IOException {
    BigDecimal breakout = riskPctEquity("manas-arora-breakout");
    BigDecimal vcp = riskPctEquity("manas-arora-vcp");

    assertThat(breakout)
        .as(
            "manas-arora-breakout and manas-arora-vcp both emit onto Books.MANAS_ARORA, so a"
                + " per-trade size that differs between them makes the portfolio cap depend on WHICH"
                + " setup happens to fire")
        .isEqualByComparingTo(vcp);
  }

  @Test
  void theDeclaredSlotCapFitsInsideThePortfolioOpenRiskCap() throws IOException {
    BigDecimal perTradePct = riskPctEquity("manas-arora-vcp");
    BigDecimal fullBookPct = perTradePct.multiply(BigDecimal.valueOf(DECLARED_SLOTS));

    assertThat(fullBookPct)
        .as(
            "%d slots × %s%% per trade = %s%% must fit inside the %s%% portfolio open-risk cap,"
                + " else the last slot is unreachable by construction (it was, at 1.0%%: 7 × 1.0 ="
                + " 7.0%% against a 6.0%% cap, so the book saturated at 6 positions)",
            DECLARED_SLOTS, perTradePct, fullBookPct, CAP_PCT)
        .isLessThanOrEqualTo(CAP_PCT);
  }

  /**
   * The knife-edge guard. 6/7 = 0.857142…% also "fits" (7 × 6/7 = 6.0 exactly), but the cap compares
   * strictly {@code >} on a value rounded to 6 decimals, so admission of the 7th would be decided by
   * rounding in the 6th decimal — reproducing the ₹7.55-of-headroom knife-edge one slot further out.
   * Require real headroom instead.
   */
  @Test
  void theFullBookLeavesGenuineHeadroomRatherThanLandingOnTheCap() throws IOException {
    BigDecimal fullBookPct =
        riskPctEquity("manas-arora-vcp").multiply(BigDecimal.valueOf(DECLARED_SLOTS));

    assertThat(CAP_PCT.subtract(fullBookPct))
        .as(
            "a full %d-slot book must sit measurably under the cap, not exactly on it — the"
                + " comparison is strictly `>` over a 6-decimal quotient",
            DECLARED_SLOTS)
        .isGreaterThanOrEqualTo(new BigDecimal("0.2"));
  }

  /**
   * Drives the REAL gate rather than the arithmetic above: a steady-state book of six positions each
   * sized at the shipped per-trade percentage must still admit a seventh.
   */
  @Test
  void theRealRiskGateAdmitsASeventhPositionOnASteadyStateBook() throws IOException {
    BigDecimal perTradePct = riskPctEquity("manas-arora-vcp");
    BigDecimal equity = new BigDecimal("152040.15"); // the live manas book, marked, 2026-08-12
    BigDecimal perTradeRiskInr =
        equity.multiply(perTradePct, MathContext.DECIMAL64).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);

    // Six existing positions, each sized at the shipped per-trade risk — the steady state this book
    // converges to once the legacy 1.0%-sized lots have turned over.
    BigDecimal existingRisk = perTradeRiskInr.multiply(BigDecimal.valueOf(DECLARED_SLOTS - 1));
    // The seventh candidate: atr_risk sizing makes qty × stopDistance == the per-trade risk budget.
    BigDecimal stopDistance = new BigDecimal("80.00");
    BigDecimal newQty = perTradeRiskInr.divide(stopDistance, 0, RoundingMode.FLOOR);

    assertThat(
            ManasPyramidPolicy.breachesRiskCap(existingRisk, newQty, stopDistance, equity, CAP_PCT))
        .as(
            "the 7th slot the book declares must actually be fillable — at risk_pct_equity 1.0 this"
                + " returned true (7 × 1.0% = 7.0% > 6.0%) and the slot was dead config")
        .isFalse();
  }

  /**
   * The discriminating counterpart: the cap must still REFUSE an eighth. A fix that simply made the
   * gate permissive would pass every assertion above.
   */
  @Test
  void theRiskGateStillRefusesAnEighthPosition() throws IOException {
    BigDecimal perTradePct = riskPctEquity("manas-arora-vcp");
    BigDecimal equity = new BigDecimal("152040.15");
    BigDecimal perTradeRiskInr =
        equity.multiply(perTradePct, MathContext.DECIMAL64).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);

    BigDecimal existingRisk = perTradeRiskInr.multiply(BigDecimal.valueOf(DECLARED_SLOTS));
    BigDecimal stopDistance = new BigDecimal("80.00");
    BigDecimal newQty = perTradeRiskInr.divide(stopDistance, 0, RoundingMode.FLOOR);

    assertThat(
            ManasPyramidPolicy.breachesRiskCap(existingRisk, newQty, stopDistance, equity, CAP_PCT))
        .as("8 × the per-trade budget must still breach the portfolio cap")
        .isTrue();
  }
}
