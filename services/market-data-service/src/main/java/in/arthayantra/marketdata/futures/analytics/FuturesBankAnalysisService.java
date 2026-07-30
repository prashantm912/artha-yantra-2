package in.arthayantra.marketdata.futures.analytics;

import in.arthayantra.marketdata.options.OiInterpretation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * oipulse "Banks Analysis" (§futures/banks-analysis) — a TIME(rows) x BANK(cols) pivot: per interval each
 * bank shows its CUMULATIVE-from-day-open (LTP% / OI%) + a PER-INTERVAL 4-state OI interpretation badge.
 *
 * <p>Two baselines, deliberately asymmetric (the faithful reading):
 * <ul>
 *   <li><b>LTP%</b> = (curLtp − the captured day-open) / day-open · 100 — uses {@code FutPoint.dayOpen}
 *       (the broker session-open, V015), strictly more faithful than a first-bucket proxy.</li>
 *   <li><b>OI%</b> = (curOi − the first captured OI of the day) / firstOi · 100 — OI has no captured
 *       day-open analog, so the first captured bucket is the baseline (sign-capable, forward-capture).</li>
 *   <li><b>Interpretation</b> = {@link OiInterpretation#classify} of (curLtp − PRIOR bucket ltp, curOi −
 *       PRIOR bucket oi) — PER-INTERVAL vs the prior bucket, NOT derived from the cumulative %s.</li>
 * </ul>
 * Per bank only its FRONT contract (most captured buckets that day; ties → nearest expiry) feeds the
 * series. Columns stay in the configured (oipulse) order; rows are newest-first.
 */
@Service
public class FuturesBankAnalysisService {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  /** One bank's cell at one interval: cumulative LTP% / OI% + the per-interval 4-state interpretation. */
  @Schema(types = {"object", "null"})
  public record BankAnalysisCell(
      String bank,
      @Schema(types = {"number", "null"}) BigDecimal ltpPct,
      @Schema(types = {"number", "null"}) BigDecimal oiPct,
      @Schema(types = {"string", "null"}) OiInterpretation interpretation) {}

  /**
   * One matrix row: a snapshot bucket + a cell per bank in the configured column order.
   *
   * <p>⚠️ KNOWN CONTRACT GAP, deliberate: {@code cells} contains NULL ELEMENTS — line ~123 does
   * {@code cells.add(byBank.get(bank).get(b))}, which is null when that bank has no point at this
   * bucket, and positional alignment with {@code banks} is the whole point of the matrix so the nulls
   * cannot be filtered out. The emitted schema says {@code items: $ref BankAnalysisCell}, i.e. it
   * claims every element is present. That is WRONG at runtime.
   *
   * <p>It is left wrong ON PURPOSE. The only annotation that expresses element-nullability here,
   * {@code @ArraySchema(schema = @Schema(types = {"object","null"}))}, was tried and it DESTROYS the
   * $ref — items collapse to a bare {@code {"type":["object","null"]}} and the entire
   * BankAnalysisCell shape disappears from the spec and the generated TS. That trades a wrong
   * nullability claim for total blindness about the element, which is the exact blindness this D3
   * conversion exists to remove. Keeping the $ref is the better of two imperfect options.
   *
   * <p>Consumers must treat a cell as possibly null. If springdoc later supports a $ref-preserving
   * nullable-items form, fix it here.
   */
  public record BankAnalysisRow(OffsetDateTime bucket, List<BankAnalysisCell> cells) {}

  /** The matrix: the ordered bank columns + the newest-first rows + the latest bucket. */
  public record BankAnalysisMatrix(
      List<String> banks,
      List<BankAnalysisRow> rows,
      @Schema(types = {"string", "null"}) OffsetDateTime asOf) {}

  /** The banks-analysis response, adding the selected interval to the matrix. */
  public record BankAnalysisResponse(
      List<String> banks,
      List<BankAnalysisRow> rows,
      String interval,
      @Schema(types = {"string", "null"}) OffsetDateTime asOf) {}

  /** Adds the requested interval without moving response-shape assembly into the controller. */
  public BankAnalysisResponse response(BankAnalysisMatrix matrix, String interval) {
    return new BankAnalysisResponse(matrix.banks(), matrix.rows(), interval, matrix.asOf());
  }

  /**
   * Pivots a multi-underlying day series into the bank matrix. {@code banks} drives BOTH the column set
   * and order; a bank absent from {@code series} contributes null cells.
   */
  public BankAnalysisMatrix matrix(
      List<FuturesSnapshotReader.FutPoint> series, List<String> banks) {
    Map<String, List<FuturesSnapshotReader.FutPoint>> byUnderlying = new LinkedHashMap<>();
    for (FuturesSnapshotReader.FutPoint p : series) {
      byUnderlying.computeIfAbsent(p.underlying(), k -> new ArrayList<>()).add(p);
    }

    TreeSet<OffsetDateTime> allBuckets = new TreeSet<>();
    Map<String, Map<OffsetDateTime, BankAnalysisCell>> byBank = new LinkedHashMap<>();
    for (String bank : banks) {
      Map<OffsetDateTime, BankAnalysisCell> cells = new LinkedHashMap<>();
      BigDecimal dayOpen = null; // LTP baseline = the captured session open
      Long firstOi = null; // OI baseline = the first captured bucket OI
      FuturesSnapshotReader.FutPoint prev = null;
      for (FuturesSnapshotReader.FutPoint p : frontPoints(byUnderlying.getOrDefault(bank, List.of()))) {
        if (dayOpen == null && p.dayOpen() != null && p.dayOpen().signum() != 0) {
          dayOpen = p.dayOpen();
        }
        if (firstOi == null && p.oi() != null) {
          firstOi = p.oi();
        }
        BigDecimal ltpPct =
            dayOpen != null && p.ltp() != null
                ? p.ltp().subtract(dayOpen).multiply(HUNDRED).divide(dayOpen, 2, RoundingMode.HALF_UP)
                : null;
        BigDecimal oiPct =
            firstOi != null && firstOi != 0 && p.oi() != null
                ? BigDecimal.valueOf(p.oi() - firstOi)
                    .multiply(HUNDRED)
                    .divide(BigDecimal.valueOf(firstOi), 2, RoundingMode.HALF_UP)
                : null;
        OiInterpretation interp = null;
        if (prev != null) {
          BigDecimal ltpDelta =
              (p.ltp() == null ? BigDecimal.ZERO : p.ltp())
                  .subtract(prev.ltp() == null ? BigDecimal.ZERO : prev.ltp());
          long oiDelta = (p.oi() == null ? 0 : p.oi()) - (prev.oi() == null ? 0 : prev.oi());
          interp = OiInterpretation.classify(ltpDelta, oiDelta);
        }
        cells.put(p.bucket(), new BankAnalysisCell(bank, ltpPct, oiPct, interp));
        allBuckets.add(p.bucket());
        prev = p;
      }
      byBank.put(bank, cells);
    }

    List<OffsetDateTime> buckets = new ArrayList<>(allBuckets);
    buckets.sort(Comparator.reverseOrder()); // newest interval first (oipulse top row)
    List<BankAnalysisRow> rows = new ArrayList<>(buckets.size());
    for (OffsetDateTime b : buckets) {
      List<BankAnalysisCell> cells = new ArrayList<>(banks.size());
      for (String bank : banks) {
        cells.add(byBank.get(bank).get(b)); // null when the bank has no point at this bucket
      }
      rows.add(new BankAnalysisRow(b, cells));
    }
    OffsetDateTime asOf = buckets.isEmpty() ? null : buckets.get(0);
    return new BankAnalysisMatrix(banks, rows, asOf);
  }

  /**
   * The active FRONT contract's points for one underlying, bucket-ordered: the tradingsymbol with the
   * most captured buckets that day, ties broken by the nearest (earliest) expiry. Mirrors the controller's
   * {@code pickContract} (inlined per CLAUDE.md surgical rule — no shared-static refactor of working code).
   */
  private static List<FuturesSnapshotReader.FutPoint> frontPoints(
      List<FuturesSnapshotReader.FutPoint> slice) {
    if (slice.isEmpty()) {
      return List.of();
    }
    Map<String, Long> counts = new LinkedHashMap<>();
    Map<String, LocalDate> exp = new LinkedHashMap<>();
    for (FuturesSnapshotReader.FutPoint p : slice) {
      counts.merge(p.tradingsymbol(), 1L, Long::sum);
      exp.putIfAbsent(p.tradingsymbol(), p.expiry());
    }
    String front =
        counts.keySet().stream()
            .max(
                Comparator.comparingLong((String s) -> counts.get(s))
                    .thenComparing(
                        s -> exp.get(s), Comparator.nullsLast(Comparator.<LocalDate>reverseOrder())))
            .orElse(null);
    return front == null
        ? List.of()
        : slice.stream()
            .filter(p -> front.equals(p.tradingsymbol()))
            .sorted(Comparator.comparing(FuturesSnapshotReader.FutPoint::bucket))
            .toList();
  }
}
