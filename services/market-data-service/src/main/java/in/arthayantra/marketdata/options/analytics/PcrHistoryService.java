package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.marketdata.options.OiInterval;
import in.arthayantra.marketdata.options.OptionsChainService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** PCR time-series over stored snapshots (reuses OptionsChainService.pcr). */
@Service
public class PcrHistoryService {

  private final OptionsSnapshotReader reader;

  public PcrHistoryService(OptionsSnapshotReader reader) {
    this.reader = reader;
  }

  public record PcrPoint(OffsetDateTime bucket, BigDecimal pcr, long ceOi, long peOi) {}

  public List<PcrPoint> history(
      String underlying,
      LocalDate expiry,
      OiInterval interval,
      OffsetDateTime from,
      OffsetDateTime to) {
    Map<OffsetDateTime, long[]> perBucket = new LinkedHashMap<>(); // [ceOi, peOi]
    for (OptionsSnapshotReader.StrikePoint p :
        reader.series(underlying, expiry, interval, from, to)) {
      long[] sums = perBucket.computeIfAbsent(p.bucket(), k -> new long[2]);
      long oi = p.oi() == null ? 0 : p.oi();
      if ("CE".equals(p.optionType())) {
        sums[0] += oi;
      } else {
        sums[1] += oi;
      }
    }
    List<PcrPoint> out = new ArrayList<>();
    perBucket.forEach(
        (bucket, sums) ->
            out.add(new PcrPoint(bucket, OptionsChainService.pcr(sums[0], sums[1]), sums[0], sums[1])));
    return out;
  }
}
