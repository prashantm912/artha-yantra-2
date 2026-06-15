package in.arthayantra.marketdata.options.analytics;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * oipulse "Premium": per-strike straddle premium (CE ltp + PE ltp) over the latest bucket, plus the
 * ATM straddle (the listed strike nearest spot). A pure LTP read — no Greeks/POP/payoff (those are
 * the deferred option-strategies feature).
 */
@Service
public class OiPremiumService {

  public record PremiumRow(BigDecimal strike, BigDecimal straddle, BigDecimal ce, BigDecimal pe) {}

  public record PremiumChain(
      List<PremiumRow> items,
      BigDecimal atmStrike,
      BigDecimal atmStraddle,
      BigDecimal spot,
      OffsetDateTime asOf) {}

  public PremiumChain premium(List<OptionsSnapshotReader.StrikePoint> latest) {
    Map<BigDecimal, BigDecimal[]> byStrike = new LinkedHashMap<>(); // [ceLtp, peLtp]
    BigDecimal spot = null;
    OffsetDateTime asOf = null;
    for (OptionsSnapshotReader.StrikePoint p : latest) {
      if (asOf == null) {
        asOf = p.bucket();
      }
      if (spot == null && p.spot() != null) {
        spot = p.spot();
      }
      BigDecimal[] v = byStrike.computeIfAbsent(p.strike(), k -> new BigDecimal[2]);
      if ("CE".equals(p.optionType())) {
        v[0] = p.ltp();
      } else {
        v[1] = p.ltp();
      }
    }
    List<PremiumRow> items = new ArrayList<>();
    for (Map.Entry<BigDecimal, BigDecimal[]> e : byStrike.entrySet()) {
      BigDecimal ce = e.getValue()[0];
      BigDecimal pe = e.getValue()[1];
      if (ce == null || pe == null) {
        continue; // a straddle needs both legs
      }
      items.add(new PremiumRow(e.getKey(), ce.add(pe), ce, pe));
    }
    items.sort(Comparator.comparing(PremiumRow::strike));
    PremiumRow atm = null;
    if (spot != null) {
      BigDecimal s = spot;
      atm =
          items.stream()
              .min(Comparator.comparing(r -> r.strike().subtract(s).abs()))
              .orElse(null);
    }
    return new PremiumChain(
        items,
        atm == null ? null : atm.strike(),
        atm == null ? null : atm.straddle(),
        spot,
        asOf);
  }
}
