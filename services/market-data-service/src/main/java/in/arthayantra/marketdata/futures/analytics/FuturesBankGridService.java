package in.arthayantra.marketdata.futures.analytics;

import in.arthayantra.marketdata.options.OiInterpretation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * oipulse "Banks Analysis" grid: one row per bank-sector underlying = its FRONT (nearest-expiry)
 * future's buildup. Distinct from {@link FuturesMoversService#banks} (a single index's term
 * structure). Reads the whole sector at once ({@link FuturesSnapshotReader#latestPairAll}); per
 * underlying it picks the nearest-expiry contract at the newest bucket and diffs it against the same
 * contract's prior bucket for price%/OI%/4-state interpretation. Rows are sorted by OI descending.
 *
 * <p>Data is forward-only (bank-stock futures OI capture went live 2026-06-16), so the grid is empty
 * until at least two snapshot buckets have accrued.
 */
@Service
public class FuturesBankGridService {

  public record BankGridRow(
      String underlying,
      String tradingsymbol,
      LocalDate expiry,
      @Schema(types = {"number", "null"}) BigDecimal ltp,
      @Schema(types = {"number", "null"}) BigDecimal pricePct,
      long oi,
      long oiChange,
      @Schema(types = {"number", "null"}) BigDecimal oiPct,
      OiInterpretation interpretation) {}

  public record BankGrid(
      List<BankGridRow> items,
      @Schema(types = {"string", "null"}) OffsetDateTime asOf) {}

  public BankGrid grid(List<FuturesSnapshotReader.FutPoint> pair) {
    OffsetDateTime newest =
        pair.stream()
            .map(FuturesSnapshotReader.FutPoint::bucket)
            .max(Comparator.naturalOrder())
            .orElse(null);
    if (newest == null) {
      return new BankGrid(List.of(), null);
    }
    OffsetDateTime prior =
        pair.stream()
            .map(FuturesSnapshotReader.FutPoint::bucket)
            .filter(b -> b.isBefore(newest))
            .max(Comparator.naturalOrder())
            .orElse(null);

    // prior bucket points keyed by tradingsymbol (for the contract-level diff).
    Map<String, FuturesSnapshotReader.FutPoint> oldBySym = new HashMap<>();
    // front (nearest-expiry) newest-bucket point per underlying.
    Map<String, FuturesSnapshotReader.FutPoint> frontByUnderlying = new HashMap<>();
    for (FuturesSnapshotReader.FutPoint p : pair) {
      if (prior != null && p.bucket().equals(prior)) {
        oldBySym.put(p.tradingsymbol(), p);
      }
      if (p.bucket().equals(newest) && p.expiry() != null && p.underlying() != null) {
        frontByUnderlying.merge(
            p.underlying(), p, (a, b) -> b.expiry().isBefore(a.expiry()) ? b : a);
      }
    }

    List<BankGridRow> rows = new ArrayList<>();
    for (FuturesSnapshotReader.FutPoint cur : frontByUnderlying.values()) {
      FuturesSnapshotReader.FutPoint old = oldBySym.get(cur.tradingsymbol());
      long curOi = cur.oi() == null ? 0 : cur.oi();
      long oiChange = 0;
      BigDecimal oiPct = null;
      OiInterpretation interp = null;
      if (old != null) {
        long priorOi = old.oi() == null ? 0 : old.oi();
        oiChange = curOi - priorOi;
        BigDecimal ltpDelta =
            (cur.ltp() == null ? BigDecimal.ZERO : cur.ltp())
                .subtract(old.ltp() == null ? BigDecimal.ZERO : old.ltp());
        interp = OiInterpretation.classify(ltpDelta, oiChange);
        oiPct =
            priorOi == 0
                ? null
                : BigDecimal.valueOf(oiChange)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(priorOi), 2, RoundingMode.HALF_UP);
      }
      rows.add(
          new BankGridRow(
              cur.underlying(),
              cur.tradingsymbol(),
              cur.expiry(),
              cur.ltp(),
              pricePct(cur, old),
              curOi,
              oiChange,
              oiPct,
              interp));
    }
    rows.sort(Comparator.comparingLong(BankGridRow::oi).reversed());
    return new BankGrid(rows, newest);
  }

  /** Day price% off the captured {@code prevClose}; falls back to the prior bucket LTP. */
  private static BigDecimal pricePct(
      FuturesSnapshotReader.FutPoint cur, FuturesSnapshotReader.FutPoint old) {
    BigDecimal base =
        cur.prevClose() != null && cur.prevClose().signum() != 0
            ? cur.prevClose()
            : (old != null && old.ltp() != null && old.ltp().signum() != 0 ? old.ltp() : null);
    if (base == null || cur.ltp() == null) {
      return null;
    }
    return cur.ltp()
        .subtract(base)
        .multiply(BigDecimal.valueOf(100))
        .divide(base, 2, RoundingMode.HALF_UP);
  }
}
