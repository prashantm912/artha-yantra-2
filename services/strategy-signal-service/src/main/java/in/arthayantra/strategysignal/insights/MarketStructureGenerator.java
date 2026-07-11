package in.arthayantra.strategysignal.insights;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * MARKET_STRUCTURE (INT design §2.2): a coarse market-regime read on the 15-min sweep. Reads the
 * day-context one-call's INDIA-VIX band + primary-index day-range regime (the SHIPPED #745 fields):
 * an ELEVATED/HIGH VIX band or a WIDE day range is a regime worth surfacing. PURE over the parsed
 * {@link MarketContext.MarketStructureView}; one insight per regime facet with a
 * {@code structureCooldownMinutes} cooldown (§2.5.2).
 *
 * <p><b>Scope (I2).</b> The design's breadth-thrust / sector-rotation MARKET_STRUCTURE inputs come
 * from the equity-digest (§6.1.3) — an I2 market-data endpoint that lands in parallel. This generator
 * reads only the day-context fields that ship today (VIX + index range) and degrades honestly rather
 * than fabricating breadth/sector; those wire in additively when the equity-digest is available.
 */
@Component
public class MarketStructureGenerator implements InsightGenerator {

  private final InsightProperties.Context cfg;

  public MarketStructureGenerator(InsightProperties props) {
    this.cfg = props.context();
  }

  @Override
  public InsightType type() {
    return InsightType.MARKET_STRUCTURE;
  }

  @Override
  public List<InsightCandidate> generate(GenerationContext ctx) {
    MarketContext market = ctx.market();
    if (market == null || market.structure() == null) {
      return List.of();
    }
    MarketContext.MarketStructureView s = market.structure();
    if ("BLOCKED".equals(s.dataTrust())) {
      return List.of();
    }
    boolean vixNotable = "ELEVATED".equals(s.vixBand()) || "HIGH".equals(s.vixBand());
    boolean rangeWide = "WIDE".equals(s.rangeState());
    if (!vixNotable && !rangeWide) {
      return List.of();
    }
    DataTrust trust = DataTrust.valueOf(s.dataTrust() == null ? "OK" : s.dataTrust());
    Severity severity = "HIGH".equals(s.vixBand()) ? Severity.WARN : Severity.NOTICE;

    List<Evidence> evidence = new ArrayList<>();
    List<String> parts = new ArrayList<>();
    if (vixNotable) {
      parts.add("VIX " + s.vixBand() + (s.vixLevel() == null ? "" : " (" + plain(s.vixLevel()) + ")"));
      evidence.add(
          Evidence.sourced("INDIA VIX", s.vixBand() + (s.vixLevel() == null ? "" : " " + plain(s.vixLevel())),
              "/api/v1/market/context/day-context", s.asOf()));
    }
    if (rangeWide) {
      parts.add(s.indexSymbol() + " range WIDE"
          + (s.rangeVsAvg() == null ? "" : " (" + plain(s.rangeVsAvg()) + "× 20-session avg)"));
      evidence.add(
          Evidence.sourced("day range vs avg", s.rangeVsAvg() == null ? "WIDE" : plain(s.rangeVsAvg()) + "×",
              "/api/v1/market/context/day-context", s.asOf()));
    }
    String summary = String.join(", ", parts);
    return List.of(
        new InsightCandidate(
            InsightType.MARKET_STRUCTURE,
            severity,
            "market",
            "Volatility regime: " + summary,
            "Market structure: " + summary + "."
                + (trust == DataTrust.DEGRADED ? " Day-context DEGRADED — treat as a caveat." : ""),
            List.copyOf(evidence),
            null, null, trust, List.of(),
            "MARKET_STRUCTURE:market:REGIME", cfg.structureCooldownMinutes(), null, false));
  }

  private static String plain(java.math.BigDecimal v) {
    return v == null ? "n/a" : v.stripTrailingZeros().toPlainString();
  }
}
