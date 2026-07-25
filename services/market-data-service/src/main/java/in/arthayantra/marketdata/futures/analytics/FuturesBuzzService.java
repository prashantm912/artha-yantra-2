package in.arthayantra.marketdata.futures.analytics;

import in.arthayantra.marketdata.options.OiInterpretation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * oipulse futures "Buzz": a time x contract heatmap of the 4-state OI interpretation. Each cell is
 * the bucket-over-bucket diff (vs the prior captured bucket of the same contract); the first bucket
 * (or a gap) yields a {@code null} cell (no signal). An empty series gives an empty matrix.
 */
@Service
public class FuturesBuzzService {

  /**
   * ⚠️ {@code cells} elements are genuinely nullable (the {@code row.add(null)} gap branch below),
   * but element-level nullability of a DOUBLY-nested generic is not expressible with the swagger
   * annotations: {@code @ArraySchema(schema=…)} collapses the inner array and drops the enum, and
   * {@code @Schema(types=…)} pollutes the OUTER type to {@code ["array","string","null"]} (both
   * measured against swagger-core 2.2.30). Left un-annotated deliberately — see the 3e section of
   * {@code docs/superpowers/plans/2026-07-25-nullable-contract-sweep.md}.
   */
  public record BuzzMatrix(
      List<String> contracts,
      List<OffsetDateTime> buckets,
      List<List<OiInterpretation>> cells,
      @Schema(types = {"string", "null"}) OffsetDateTime asOf) {}

  public BuzzMatrix buzz(List<FuturesSnapshotReader.FutPoint> series) {
    List<OffsetDateTime> buckets =
        series.stream().map(FuturesSnapshotReader.FutPoint::bucket).distinct().sorted().toList();
    List<String> contracts =
        series.stream()
            .map(FuturesSnapshotReader.FutPoint::tradingsymbol)
            .distinct()
            .sorted()
            .toList();
    if (buckets.isEmpty()) {
      return new BuzzMatrix(List.of(), List.of(), List.of(), null);
    }
    Map<String, Map<OffsetDateTime, FuturesSnapshotReader.FutPoint>> by = new HashMap<>();
    for (FuturesSnapshotReader.FutPoint p : series) {
      by.computeIfAbsent(p.tradingsymbol(), k -> new HashMap<>()).put(p.bucket(), p);
    }
    List<List<OiInterpretation>> cells = new ArrayList<>();
    for (int i = 0; i < buckets.size(); i++) {
      OffsetDateTime b = buckets.get(i);
      OffsetDateTime prevB = i == 0 ? null : buckets.get(i - 1);
      List<OiInterpretation> row = new ArrayList<>();
      for (String c : contracts) {
        Map<OffsetDateTime, FuturesSnapshotReader.FutPoint> ofContract = by.getOrDefault(c, Map.of());
        FuturesSnapshotReader.FutPoint cur = ofContract.get(b);
        FuturesSnapshotReader.FutPoint old = prevB == null ? null : ofContract.get(prevB);
        if (cur == null || old == null) {
          row.add(null);
          continue;
        }
        long oiDelta = (cur.oi() == null ? 0 : cur.oi()) - (old.oi() == null ? 0 : old.oi());
        BigDecimal ltpDelta =
            (cur.ltp() == null ? BigDecimal.ZERO : cur.ltp())
                .subtract(old.ltp() == null ? BigDecimal.ZERO : old.ltp());
        row.add(OiInterpretation.classify(ltpDelta, oiDelta));
      }
      cells.add(row);
    }
    return new BuzzMatrix(contracts, buckets, cells, buckets.get(buckets.size() - 1));
  }
}
