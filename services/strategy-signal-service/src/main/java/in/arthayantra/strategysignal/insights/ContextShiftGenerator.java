package in.arthayantra.strategysignal.insights;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * CONTEXT_SHIFT (INT design §2.2/§6.2): on the 15-min sweep, emit when a market-context digest
 * crosses a config-pinned threshold since session-open — |ΔPCR| ≥ {@code pcrDelta}, |max-pain drift|
 * ≥ {@code maxPainDrift}, |straddle %| ≥ {@code straddlePct}, active-strike set change ≥
 * {@code activeStrikeChange}, and the primary index gap-open ≥ {@code gapOpenPct}. The generator is
 * PURE over the parsed {@link MarketContext}: it reads the digest's OWN baseline-relative delta and
 * checks the crossing (§6.2, "each threshold and its baseline is named in the insight evidence"), so
 * it never persists a baseline. BLOCKED / historical / derived digests never trigger (§7.4). One
 * insight per (underlying, metric) with a {@code shiftCooldownMinutes} cooldown (§2.5.2).
 */
@Component
public class ContextShiftGenerator implements InsightGenerator {

  private final InsightProperties.Context cfg;

  public ContextShiftGenerator(InsightProperties props) {
    this.cfg = props.context();
  }

  @Override
  public InsightType type() {
    return InsightType.CONTEXT_SHIFT;
  }

  @Override
  public List<InsightCandidate> generate(GenerationContext ctx) {
    MarketContext market = ctx.market();
    if (market == null) {
      return List.of();
    }
    List<InsightCandidate> out = new ArrayList<>();
    for (MarketContext.OptionsShift os : market.optionsShifts()) {
      if (os == null || "BLOCKED".equals(os.dataTrust())) {
        continue; // never rank/shift on a blocked digest (§7.4)
      }
      out.addAll(optionsCrossings(os));
    }
    MarketContext.MarketStructureView s = market.structure();
    if (s != null && !"BLOCKED".equals(s.dataTrust())) {
      indexCrossing(s).ifPresent(out::add);
    }
    return out;
  }

  private List<InsightCandidate> optionsCrossings(MarketContext.OptionsShift os) {
    List<InsightCandidate> out = new ArrayList<>();
    String scope = "underlying:" + os.exchange() + ":" + os.underlying();
    DataTrust trust = DataTrust.valueOf(os.dataTrust() == null ? "OK" : os.dataTrust());

    if (crosses(os.pcrDeltaVsOpen(), cfg.pcrDelta())) {
      String dir = os.pcrDeltaVsOpen().signum() >= 0 ? "rose" : "fell";
      out.add(
          candidate(
              os, scope, trust, "PCR",
              os.underlying() + " PCR " + dir + " " + plain(os.pcrDeltaVsOpen().abs()) + " since open",
              "PCR " + dir + " " + plain(os.pcrDeltaVsOpen().abs()) + " since session-open (now "
                  + plain(os.pcrNow()) + "), past the " + plain(cfg.pcrDelta()) + " threshold.",
              List.of(
                  Evidence.sourced("PCR Δ vs open", plain(os.pcrDeltaVsOpen()),
                      "/api/v1/market/context/options-digest", params(os), os.asOf()),
                  Evidence.of("PCR now", plain(os.pcrNow())))));
    }
    if (crosses(os.maxPainDrift(), cfg.maxPainDrift())) {
      out.add(
          candidate(
              os, scope, trust, "MAXPAIN",
              os.underlying() + " max-pain drifted " + plain(os.maxPainDrift()) + " since open",
              "Max-pain drifted " + plain(os.maxPainDrift()) + " index points since session-open, past"
                  + " the " + plain(cfg.maxPainDrift()) + "-point threshold.",
              List.of(
                  Evidence.sourced("max-pain drift", plain(os.maxPainDrift()),
                      "/api/v1/market/context/options-digest", params(os), os.asOf()))));
    }
    if (crosses(os.straddleDeltaPct(), cfg.straddlePct())) {
      String dir = os.straddleDeltaPct().signum() >= 0 ? "expanded" : "compressed";
      out.add(
          candidate(
              os, scope, trust, "STRADDLE",
              os.underlying() + " ATM straddle " + dir + " " + plain(os.straddleDeltaPct().abs()) + "%",
              "The ATM straddle " + dir + " " + plain(os.straddleDeltaPct().abs()) + "% vs session-open,"
                  + " past the " + plain(cfg.straddlePct()) + "% threshold.",
              List.of(
                  Evidence.sourced("straddle Δ% vs open", plain(os.straddleDeltaPct()),
                      "/api/v1/market/context/options-digest", params(os), os.asOf()))));
    }
    if (os.activeStrikeChanged() != null && os.activeStrikeChanged() >= cfg.activeStrikeChange()) {
      out.add(
          candidate(
              os, scope, trust, "ACTIVE_STRIKES",
              os.underlying() + " active-strike set changed " + os.activeStrikeChanged() + " strikes",
              os.activeStrikeChanged() + " of the top peak-OI strikes changed vs session-open, past the "
                  + cfg.activeStrikeChange() + "-strike threshold.",
              List.of(
                  Evidence.sourced("active-strike changes", String.valueOf(os.activeStrikeChanged()),
                      "/api/v1/market/context/options-digest", params(os), os.asOf()))));
    }
    return out;
  }

  private java.util.Optional<InsightCandidate> indexCrossing(MarketContext.MarketStructureView s) {
    if (s.gapOpenPct() == null || s.gapOpenPct().abs().compareTo(cfg.gapOpenPct()) < 0) {
      return java.util.Optional.empty();
    }
    String dir = s.gapOpenPct().signum() >= 0 ? "gapped up" : "gapped down";
    DataTrust trust = DataTrust.valueOf(s.dataTrust() == null ? "OK" : s.dataTrust());
    return java.util.Optional.of(
        new InsightCandidate(
            InsightType.CONTEXT_SHIFT,
            Severity.NOTICE,
            "market",
            s.indexSymbol() + " " + dir + " " + plain(s.gapOpenPct().abs()) + "% at open",
            s.indexSymbol() + " " + dir + " " + plain(s.gapOpenPct().abs()) + "% vs prior close, past the "
                + plain(cfg.gapOpenPct()) + "% gap threshold.",
            List.of(
                Evidence.sourced("gap-open %", plain(s.gapOpenPct()),
                    "/api/v1/market/context/day-context", s.asOf())),
            null, null, trust, List.of(),
            "CONTEXT_SHIFT:market:GAP_OPEN", cfg.shiftCooldownMinutes(), null, false));
  }

  private InsightCandidate candidate(
      MarketContext.OptionsShift os, String scope, DataTrust trust, String metric,
      String title, String explanation, List<Evidence> evidence) {
    return new InsightCandidate(
        InsightType.CONTEXT_SHIFT,
        trust == DataTrust.DEGRADED ? Severity.INFO : Severity.NOTICE,
        scope,
        title,
        explanation + (trust == DataTrust.DEGRADED ? " Digest DEGRADED — treat as a caveat." : ""),
        evidence,
        null, null, trust, os.trustReasons() == null ? List.of() : os.trustReasons(),
        "CONTEXT_SHIFT:" + scope + ":" + metric, cfg.shiftCooldownMinutes(), null, false);
  }

  private static java.util.Map<String, Object> params(MarketContext.OptionsShift os) {
    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("name", os.underlying());
    if (os.expiry() != null) {
      m.put("expiry", os.expiry());
    }
    return m;
  }

  private static boolean crosses(BigDecimal delta, BigDecimal threshold) {
    return delta != null && delta.abs().compareTo(threshold) >= 0;
  }

  private static String plain(BigDecimal v) {
    return v == null ? "n/a" : v.stripTrailingZeros().toPlainString();
  }
}
