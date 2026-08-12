package in.arthayantra.marketdata.futures.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import in.arthayantra.marketdata.freshness.DataFreshness;
import in.arthayantra.marketdata.options.OiInterpretation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Futures analogue of {@link in.arthayantra.marketdata.options.analytics.OiSpurtService}: per
 * contract, diff the latest two snapshot buckets (from {@link FuturesSnapshotReader#latestPair})
 * into a 4-state interpretation + OI-spurt %. Contracts present in only the newest bucket (no prior
 * to diff) are skipped; {@code asOf} is the newest bucket (null only when the window is empty).
 */
@Service
public class FuturesSpurtService {

  public record FutSpurt(
      String tradingsymbol,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal ltp,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal prevClose,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal pricePct,
      long oi,
      long oiChange,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal spurtPct,
      OiInterpretation interpretation) {}

  public record FutSpurtChain(
      List<FutSpurt> items,
      @Schema(types = {"string", "null"}) OffsetDateTime asOf,
      @JsonInclude(JsonInclude.Include.NON_NULL) DataFreshness freshness) {
    public FutSpurtChain(List<FutSpurt> items, OffsetDateTime asOf) {
      this(items, asOf, null);
    }

    /** Returns a copy carrying the freshness envelope (populated at the controller boundary). */
    public FutSpurtChain withFreshness(DataFreshness f) {
      return new FutSpurtChain(items, asOf, f);
    }
  }

  public FutSpurtChain spurts(List<FuturesSnapshotReader.FutPoint> pair) {
    OffsetDateTime newest =
        pair.stream()
            .map(FuturesSnapshotReader.FutPoint::bucket)
            .max(Comparator.naturalOrder())
            .orElse(null);
    if (newest == null) {
      return new FutSpurtChain(List.of(), null);
    }
    OffsetDateTime prior =
        pair.stream()
            .map(FuturesSnapshotReader.FutPoint::bucket)
            .filter(b -> b.isBefore(newest))
            .max(Comparator.naturalOrder())
            .orElse(null);
    if (prior == null) {
      return new FutSpurtChain(List.of(), newest);
    }
    Map<String, FuturesSnapshotReader.FutPoint> newAt = new HashMap<>();
    Map<String, FuturesSnapshotReader.FutPoint> oldAt = new HashMap<>();
    for (FuturesSnapshotReader.FutPoint p : pair) {
      if (p.bucket().equals(newest)) {
        newAt.put(p.tradingsymbol(), p);
      } else if (p.bucket().equals(prior)) {
        oldAt.put(p.tradingsymbol(), p);
      }
    }
    List<FutSpurt> items = new ArrayList<>();
    for (Map.Entry<String, FuturesSnapshotReader.FutPoint> e : newAt.entrySet()) {
      FuturesSnapshotReader.FutPoint cur = e.getValue();
      FuturesSnapshotReader.FutPoint old = oldAt.get(e.getKey());
      if (old == null) {
        continue;
      }
      long priorOi = old.oi() == null ? 0 : old.oi();
      long curOi = cur.oi() == null ? 0 : cur.oi();
      long oiDelta = curOi - priorOi;
      BigDecimal ltpDelta =
          (cur.ltp() == null ? BigDecimal.ZERO : cur.ltp())
              .subtract(old.ltp() == null ? BigDecimal.ZERO : old.ltp());
      BigDecimal spurtPct =
          priorOi == 0
              ? null
              : BigDecimal.valueOf(oiDelta)
                  .multiply(BigDecimal.valueOf(100))
                  .divide(BigDecimal.valueOf(priorOi), 2, RoundingMode.HALF_UP);
      items.add(
          new FutSpurt(
              cur.tradingsymbol(),
              cur.ltp(),
              cur.prevClose(),
              pricePct(cur, old),
              curOi,
              oiDelta,
              spurtPct,
              OiInterpretation.classify(ltpDelta, oiDelta)));
    }
    items.sort(Comparator.comparing(FutSpurt::tradingsymbol));
    return new FutSpurtChain(items, newest);
  }

  /**
   * Signed day price % — prefers the captured {@code prevClose} (Stage-G OHLC), falling back to the
   * prior snapshot bucket's LTP. Null when no usable base or no current LTP (parity with
   * {@link FuturesMoversService#pricePct}).
   */
  private static BigDecimal pricePct(
      FuturesSnapshotReader.FutPoint cur, FuturesSnapshotReader.FutPoint old) {
    BigDecimal base =
        cur.prevClose() != null && cur.prevClose().signum() != 0
            ? cur.prevClose()
            : (old.ltp() != null && old.ltp().signum() != 0 ? old.ltp() : null);
    if (base == null || cur.ltp() == null) {
      return null;
    }
    return cur.ltp()
        .subtract(base)
        .multiply(BigDecimal.valueOf(100))
        .divide(base, 2, RoundingMode.HALF_UP);
  }
}
