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
      double rho) {}

  private static final List<Vector> VECTORS = new ArrayList<>();

  @BeforeAll
  static void loadFixture() throws Exception {
    try (InputStream in =
        Black76GoldenVectorTest.class.getResourceAsStream("/black76-golden-vectors.json")) {
      JsonNode root = new ObjectMapper().readTree(in);
      assertThat(root.path("fixtureFormat").asInt()).isEqualTo(1);
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
                Double.parseDouble(v.path("rho").asText())));
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
    }
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
