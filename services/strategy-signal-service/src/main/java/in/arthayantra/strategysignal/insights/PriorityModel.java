package in.arthayantra.strategysignal.insights;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The signal priority model (INT design §3.2) — a PURE function of {@link SignalPriorityInputs} +
 * the resolved config. It does NOT re-score the strategy (§0 hard boundary): it normalizes the
 * strategy's OWN edge margin and layers context/track/risk/freshness on top, then applies a
 * multiplicative trust cap.
 *
 * <p>{@code priority = min(100, Σ wᵢ·cᵢ·100) × trustCap}, weights sum 1.00, every {@code cᵢ ∈ [0,1]}
 * after all bonuses/penalties. {@code trustCap}: OK → 1.0, DEGRADED → 0.79 (mechanically caps the
 * ceiling at B-band), BLOCKED → no score (never rank on bad data, §7.4). Bands A≥80 / B≥60 / C≥40 /
 * D&lt;40.
 *
 * <p><b>On the §11 worked example.</b> Edge/context reproduce exactly from the raw inputs (composite
 * vs threshold over the family band; confluence-dot fraction). The track-record and risk/freshness
 * curves in §3.2 are described, not pinned to a formula, so the exact illustrative c-values there
 * (0.68 / 0.81 / 1.0) are not reproduced bit-for-bit; this model implements §3.2's described
 * STRUCTURE deterministically and the golden test (§10.1) pins these curves.
 */
public class PriorityModel {

  /** The result of a priority evaluation: a scored detail, or a blocked verdict (no score). */
  public record Result(BigDecimal score, String band, BigDecimal trustCap, PriorityDetail detail, boolean blocked) {

    static Result blocked(BigDecimal degradedCap) {
      return new Result(null, null, degradedCap, null, true);
    }
  }

  private final InsightProperties.Priority cfg;

  public PriorityModel(InsightProperties.Priority cfg) {
    this.cfg = cfg;
  }

  /** Evaluate the five components → score/band/detail, or a BLOCKED result when trust is BLOCKED. */
  public Result evaluate(SignalPriorityInputs in) {
    if (in.trust() == DataTrust.BLOCKED) {
      return Result.blocked(cfg.degradedTrustCap());
    }
    InsightProperties.Weights w = cfg.weights();
    List<PriorityDetail.Component> components = new ArrayList<>(5);
    components.add(edge(in, w.edge()));
    components.add(context(in, w.context()));
    components.add(track(in, w.track()));
    components.add(risk(in, w.risk()));
    components.add(freshness(in, w.freshness()));

    BigDecimal rawScore = BigDecimal.ZERO;
    for (PriorityDetail.Component c : components) {
      rawScore = rawScore.add(c.points());
    }
    rawScore = rawScore.min(new BigDecimal("100"));
    BigDecimal trustCap =
        in.trust() == DataTrust.DEGRADED ? cfg.degradedTrustCap() : BigDecimal.ONE;
    BigDecimal score = scale2(rawScore.multiply(trustCap));
    String band = band(score);
    return new Result(score, band, trustCap, new PriorityDetail(score, band, trustCap, components), false);
  }

  // ---- components ------------------------------------------------------------------------------

  /** Edge margin (§3.2): (composite − threshold) normalized into the family's configured band. */
  private PriorityDetail.Component edge(SignalPriorityInputs in, BigDecimal weight) {
    BigDecimal bandWidth = cfg.edgeBandFor(in.family());
    double margin = in.composite().subtract(in.threshold()).doubleValue();
    double c = bandWidth.signum() == 0 ? 0.0 : margin / bandWidth.doubleValue();
    Evidence ev =
        Evidence.sourced(
            "composite vs threshold",
            plain(in.composite()) + " vs " + plain(in.threshold()),
            "/api/v1/signals/" + in.signalId(),
            in.asOf());
    return component("edge", weight, clamp01(c), List.of(ev));
  }

  /** Context alignment (§3.2): scalper confluence-dot fraction, or swing RS percentile (§13 row 12). */
  private PriorityDetail.Component context(SignalPriorityInputs in, BigDecimal weight) {
    List<Evidence> ev = new ArrayList<>();
    double c;
    if ("scalper".equals(in.family())) {
      BigDecimal conf = in.scalperConfluence();
      c = conf == null ? 0.5 : conf.doubleValue();
      ev.add(Evidence.of("confluence agreement", conf == null ? "unavailable (neutral 0.5)" : pct(conf)));
    } else {
      BigDecimal rs = in.swingRsPercentile();
      c = rs == null ? 0.5 : rs.doubleValue();
      ev.add(
          Evidence.of(
              "RS-rank percentile",
              rs == null ? (in.contextNote() == null ? "RS unavailable on API (neutral 0.5)" : in.contextNote()) : pct(rs)));
    }
    return component("context", weight, clamp01(c), List.copyOf(ev));
  }

  /**
   * Track record (§3.2): scaled PF (1.0→0, 2.0→1.0), never boosting an unproven strategy (&lt;20
   * closed trades → neutral 0.5) nor a negative-expectancy one (caps at 0.5), +0.1 TAKE_ELIGIBLE.
   */
  private PriorityDetail.Component track(SignalPriorityInputs in, BigDecimal weight) {
    List<Evidence> ev = new ArrayList<>();
    double c;
    Integer n = in.closedTrades();
    if (n == null || n < cfg.closedTradesFloor()) {
      c = 0.5;
      ev.add(Evidence.of("track record", (n == null ? "unavailable" : n + " closed trades") + " — neutral prior"));
    } else {
      double pf = in.trackPf() == null ? 1.0 : in.trackPf().doubleValue();
      double base = clamp01(pf - 1.0);
      if (in.trackExpectancy() != null && in.trackExpectancy().signum() <= 0) {
        base = Math.min(base, 0.5); // negative/zero expectancy never boosts above neutral
      }
      if (in.takeEligible()) {
        base += 0.10;
      }
      c = clamp01(base);
      ev.add(Evidence.of("PF / trades", plain(in.trackPf()) + " over " + n + " trades" + (in.takeEligible() ? " (TAKE_ELIGIBLE)" : "")));
    }
    return component("track", weight, c, List.copyOf(ev));
  }

  /**
   * Risk headroom (§3.2): {@code 1 − heat%/cap}, −0.25 concentration penalty, {@code advisedLots==0}
   * → 0. Fallbacks: unpriced (heat null) → neutral 0.5; cap unset → heat vs 100% headroom.
   */
  private PriorityDetail.Component risk(SignalPriorityInputs in, BigDecimal weight) {
    List<Evidence> ev = new ArrayList<>();
    double c;
    if (in.advisedLots() != null && in.advisedLots() == 0) {
      c = 0.0;
      ev.add(Evidence.of("advised lots", "0 — position not fundable"));
    } else if (in.heatPct() == null) {
      c = 0.5;
      ev.add(Evidence.of("book heat", "unpriced — neutral 0.5"));
    } else {
      double heat = in.heatPct().doubleValue();
      double cap = in.capPct() == null ? 100.0 : in.capPct().doubleValue();
      c = cap <= 0 ? 0.5 : 1.0 - heat / cap;
      ev.add(
          Evidence.sourced(
              "book heat",
              plain(in.heatPct()) + "%" + (in.capPct() == null ? " (no cap)" : " of " + plain(in.capPct()) + "% cap"),
              "/api/v1/paper/margin-heat",
              in.asOf()));
      if (in.sameUnderlyingSideOpen()) {
        c -= 0.25;
        ev.add(Evidence.of("concentration", "same underlying+side already open (−0.25)"));
      }
    }
    return component("risk", weight, clamp01(c), List.copyOf(ev));
  }

  /** Freshness/urgency (§3.2): half-life decay on signal age; scalper urgency tapers near close. */
  private PriorityDetail.Component freshness(SignalPriorityInputs in, BigDecimal weight) {
    double halfLife = cfg.halfLifeFor(in.family());
    double c = halfLife <= 0 ? 1.0 : Math.pow(2.0, -in.ageSeconds() / halfLife);
    List<Evidence> ev = new ArrayList<>();
    ev.add(Evidence.of("age", in.ageSeconds() + "s vs " + (long) halfLife + "s half-life"));
    if ("scalper".equals(in.family()) && in.minutesToClose() != null && in.minutesToClose() < 30) {
      double taper = clamp01(in.minutesToClose() / 30.0);
      c *= taper;
      ev.add(Evidence.of("session close", in.minutesToClose() + " min to close — urgency tapered"));
    }
    return component("freshness", weight, clamp01(c), List.copyOf(ev));
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private static PriorityDetail.Component component(
      String key, BigDecimal weight, double c, List<Evidence> evidence) {
    BigDecimal cScaled = scale4(BigDecimal.valueOf(c));
    BigDecimal points = scale2(weight.multiply(cScaled).multiply(new BigDecimal("100")));
    return new PriorityDetail.Component(key, weight, cScaled, points, evidence);
  }

  private String band(BigDecimal score) {
    InsightProperties.Bands b = cfg.bands();
    int s = score.intValue();
    if (s >= b.a()) {
      return "A";
    }
    if (s >= b.b()) {
      return "B";
    }
    if (s >= b.c()) {
      return "C";
    }
    return "D";
  }

  private static double clamp01(double v) {
    return v < 0 ? 0 : (v > 1 ? 1 : v);
  }

  private static BigDecimal scale2(BigDecimal v) {
    return v.setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal scale4(BigDecimal v) {
    return v.setScale(4, RoundingMode.HALF_UP);
  }

  private static String plain(BigDecimal v) {
    return v == null ? "n/a" : v.stripTrailingZeros().toPlainString();
  }

  private static String pct(BigDecimal fraction01) {
    return scale2(fraction01.multiply(new BigDecimal("100"))).stripTrailingZeros().toPlainString() + "%";
  }
}
