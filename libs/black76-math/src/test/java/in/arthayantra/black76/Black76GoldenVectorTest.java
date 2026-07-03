package in.arthayantra.black76;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * THE S1 GATE (B-10): the Java solver pinned against ~500 committed py_vollib golden vectors
 * (offline-generated, amendment A4 — never at test runtime). Tolerances: relative ≤ 1e-6, or
 * absolute ≤ 1e-9 where |reference| < 1e-3 (magnitude-appropriate — far-OTM gamma/vega). IV
 * round-trip: reprice within ₹0.01. Phase 15's computed-IV columns stay enabled only while this
 * suite is green.
 */
class Black76GoldenVectorTest {

  record Vector(
      Black76.OptionType type,
      double f,
      double k,
      double t,
      double sigma,
      double r,
      double price,
      double delta,
      double gamma,
      double theta,
      double vega,
      double rho,
      double vanna,
      double charm,
      double vomma) {}

  private static final List<Vector> VECTORS = new ArrayList<>();

  @BeforeAll
  static void loadFixture() throws Exception {
    try (InputStream in =
        Black76GoldenVectorTest.class.getResourceAsStream("/black76-golden-vectors.json")) {
      JsonNode root = new ObjectMapper().readTree(in);
      // fixtureFormat 2 adds the second-order trio (vanna/charm/vomma) to every vector (§17.6).
      assertThat(root.path("fixtureFormat").asInt()).isEqualTo(2);
      for (JsonNode v : root.path("vectors")) {
        VECTORS.add(
            new Vector(
                Black76.OptionType.valueOf(v.path("type").asText()),
                Double.parseDouble(v.path("F").asText()),
                Double.parseDouble(v.path("K").asText()),
                Double.parseDouble(v.path("T").asText()),
                Double.parseDouble(v.path("sigma").asText()),
                Double.parseDouble(v.path("r").asText()),
                Double.parseDouble(v.path("price").asText()),
                Double.parseDouble(v.path("delta").asText()),
                Double.parseDouble(v.path("gamma").asText()),
                Double.parseDouble(v.path("theta").asText()),
                Double.parseDouble(v.path("vega").asText()),
                Double.parseDouble(v.path("rho").asText()),
                Double.parseDouble(v.path("vanna").asText()),
                Double.parseDouble(v.path("charm").asText()),
                Double.parseDouble(v.path("vomma").asText())));
      }
    }
    assertThat(VECTORS).hasSizeGreaterThanOrEqualTo(490);
  }

  private static void assertWithinTolerance(String what, Vector v, double actual, double reference) {
    if (Math.abs(reference) < 1e-3) {
      assertThat(Math.abs(actual - reference))
          .as("%s absolute (ref %s) for %s F/K=%.2f T=%.6f sigma=%.2f",
              what, reference, v.type(), v.f() / v.k(), v.t(), v.sigma())
          .isLessThanOrEqualTo(1e-9);
    } else {
      assertThat(Math.abs(actual - reference) / Math.abs(reference))
          .as("%s relative (ref %s) for %s F/K=%.2f T=%.6f sigma=%.2f",
              what, reference, v.type(), v.f() / v.k(), v.t(), v.sigma())
          .isLessThanOrEqualTo(1e-6);
    }
  }

  @Test
  void priceAndAllGreeksMatchPyVollibAcrossTheGrid() {
    for (Vector v : VECTORS) {
      Black76.Greeks g = Black76.greeks(v.type(), v.f(), v.k(), v.t(), v.r(), v.sigma());
      assertWithinTolerance("price", v, g.price().doubleValue(), v.price());
      assertWithinTolerance("delta", v, g.delta().doubleValue(), v.delta());
      assertWithinTolerance("gamma", v, g.gamma().doubleValue(), v.gamma());
      assertWithinTolerance("theta", v, g.theta().doubleValue(), v.theta());
      assertWithinTolerance("vega", v, g.vega().doubleValue(), v.vega());
      assertWithinTolerance("rho", v, g.rho().doubleValue(), v.rho());
      assertWithinTolerance("vanna", v, g.vanna().doubleValue(), v.vanna());
      assertWithinTolerance("charm", v, g.charm().doubleValue(), v.charm());
      assertWithinTolerance("vomma", v, g.vomma().doubleValue(), v.vomma());
    }
  }

  /**
   * Independent proof the second-order closed forms are correct: each must match a CENTRAL finite
   * difference of the first-order greeks the existing golden vectors already pin —
   * vanna≡∂Δ/∂σ≡∂vega/∂F, charm≡∂Δ/∂t (per day), vomma≡∂vega/∂σ — in the SAME reporting units. Run
   * only on vectors whose greeks carry meaningful magnitude (FD is dominated by truncation/roundoff
   * in the far-OTM tails where |reference| ≈ 0), bumping σ/T off the grid so the step is clean.
   */
  @Test
  void secondOrderGreeksMatchFiniteDifferenceOfFirstOrder() {
    double[][] grid = {{20000, 30.0 / 365}, {22000, 30.0 / 365}, {24000, 7.0 / 365},
        {22000, 90.0 / 365}, {21000, 60.0 / 365}, {23000, 14.0 / 365}};
    double f = 22000;
    double r = 0.065;
    int checked = 0;
    for (double sigma : new double[] {0.12, 0.20, 0.35, 0.55}) {
      for (double[] gk : grid) {
        double k = gk[0];
        double t = gk[1];
        for (Black76.OptionType type : Black76.OptionType.values()) {
          Black76.Greeks g = Black76.greeks(type, f, k, t, r, sigma);

          double stepSigma = 1e-5;
          double fdVanna =
              (Black76.greeks(type, f, k, t, r, sigma + stepSigma).delta().doubleValue()
                      - Black76.greeks(type, f, k, t, r, sigma - stepSigma).delta().doubleValue())
                  / (2 * stepSigma)
                  / 100.0; // delta is unit-less; vanna reports per vol-point
          double fdVomma =
              (Black76.greeks(type, f, k, t, r, sigma + stepSigma).vega().doubleValue()
                      - Black76.greeks(type, f, k, t, r, sigma - stepSigma).vega().doubleValue())
                  / (2 * stepSigma)
                  / 100.0; // vega already per vol-point; ∂/∂σ adds one more /100
          double stepT = 1e-8;
          double fdCharm =
              (Black76.greeks(type, f, k, t + stepT, r, sigma).delta().doubleValue()
                      - Black76.greeks(type, f, k, t - stepT, r, sigma).delta().doubleValue())
                  / (2 * stepT)
                  / 365.0; // ∂Δ/∂t per calendar day

          assertCloseToFd("vanna", type, k, t, sigma, g.vanna().doubleValue(), fdVanna);
          assertCloseToFd("vomma", type, k, t, sigma, g.vomma().doubleValue(), fdVomma);
          assertCloseToFd("charm", type, k, t, sigma, g.charm().doubleValue(), fdCharm);
          checked++;
        }
      }
    }
    assertThat(checked).isEqualTo(48);
  }

  /**
   * Independent proof the THIRD-order closed forms are correct: speed/zomma/color are ∂Γ/∂F, ∂Γ/∂σ
   * and ∂Γ/∂t, so each must match a CENTRAL finite difference of the code's OWN gamma in the SAME
   * reporting units (speed raw per-F, zomma ÷100 per vol-point, color ÷365 per day) — same grid +
   * tolerance discipline as the second-order proof.
   */
  @Test
  void thirdOrderGreeksMatchFiniteDifferenceOfGamma() {
    double[][] grid = {{20000, 30.0 / 365}, {22000, 30.0 / 365}, {24000, 7.0 / 365},
        {22000, 90.0 / 365}, {21000, 60.0 / 365}, {23000, 14.0 / 365}};
    double f = 22000;
    double r = 0.065;
    int checked = 0;
    for (double sigma : new double[] {0.12, 0.20, 0.35, 0.55}) {
      for (double[] gk : grid) {
        double k = gk[0];
        double t = gk[1];
        for (Black76.OptionType type : Black76.OptionType.values()) {
          Black76.Greeks g = Black76.greeks(type, f, k, t, r, sigma);

          double stepF = 1.0; // F ≈ 22000 → ~5e-5 relative; gamma smooth in F
          double fdSpeed =
              (Black76.greeks(type, f + stepF, k, t, r, sigma).gamma().doubleValue()
                      - Black76.greeks(type, f - stepF, k, t, r, sigma).gamma().doubleValue())
                  / (2 * stepF); // gamma is raw per-F; speed reports the same scale
          double stepSigma = 1e-5;
          double fdZomma =
              (Black76.greeks(type, f, k, t, r, sigma + stepSigma).gamma().doubleValue()
                      - Black76.greeks(type, f, k, t, r, sigma - stepSigma).gamma().doubleValue())
                  / (2 * stepSigma)
                  / 100.0; // ∂Γ/∂σ per vol-point
          double stepT = 1e-8;
          double fdColor =
              (Black76.greeks(type, f, k, t + stepT, r, sigma).gamma().doubleValue()
                      - Black76.greeks(type, f, k, t - stepT, r, sigma).gamma().doubleValue())
                  / (2 * stepT)
                  / 365.0; // ∂Γ/∂t per calendar day

          assertCloseToFd("speed", type, k, t, sigma, g.speed().doubleValue(), fdSpeed);
          assertCloseToFd("zomma", type, k, t, sigma, g.zomma().doubleValue(), fdZomma);
          assertCloseToFd("color", type, k, t, sigma, g.color().doubleValue(), fdColor);
          checked++;
        }
      }
    }
    assertThat(checked).isEqualTo(48);
  }

  private static void assertCloseToFd(
      String what, Black76.OptionType type, double k, double t, double sigma, double analytic,
      double fd) {
    // 5e-4 relative absorbs the central-difference truncation error; the grid avoids the near-zero
    // tails where FD is pure roundoff.
    assertThat(Math.abs(analytic - fd) / Math.max(Math.abs(fd), 1e-9))
        .as("%s vs finite-difference for %s K=%.0f T=%.4f sigma=%.2f (analytic %s, fd %s)",
            what, type, k, t, sigma, analytic, fd)
        .isLessThanOrEqualTo(5e-4);
  }

  @Test
  void ivRoundTripRepricesWithinOnePaisa() {
    int solved = 0;
    for (Vector v : VECTORS) {
      double discountedIntrinsic =
          Math.exp(-v.r() * v.t())
              * Math.max(v.type() == Black76.OptionType.CE ? v.f() - v.k() : v.k() - v.f(), 0);
      if (v.price() - discountedIntrinsic < 0.05) {
        continue; // sub-tick TIME VALUE carries no recoverable vol information
      }
      IvSolver.IvResult result = IvSolver.solve(v.type(), v.f(), v.k(), v.t(), v.r(), v.price());
      assertThat(result.reason())
          .as("solvable vector %s F/K=%.2f T=%.6f sigma=%.2f", v.type(), v.f() / v.k(), v.t(), v.sigma())
          .isEqualTo(IvSolver.Reason.OK);
      double reprice =
          Black76.price(v.type(), v.f(), v.k(), v.t(), v.r(), result.iv().doubleValue());
      assertThat(Math.abs(reprice - v.price()))
          .as("round-trip reprice for %s sigma=%.2f", v.type(), v.sigma())
          .isLessThanOrEqualTo(0.01);
      solved++;
    }
    // 324 of 490 vectors carry >= one tick of time value; the 0.5d/2d far-OTM corners do not
    assertThat(solved).as("every vector with real time value must round-trip").isGreaterThan(300);
  }

  @Test
  void solverIsDeterministicAcrossRuns() {
    Vector v = VECTORS.get(VECTORS.size() / 2);
    Black76.Greeks first = Black76.greeks(v.type(), v.f(), v.k(), v.t(), v.r(), v.sigma());
    Black76.Greeks second = Black76.greeks(v.type(), v.f(), v.k(), v.t(), v.r(), v.sigma());
    assertThat(first).isEqualTo(second);
  }
}
