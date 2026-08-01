package in.arthayantra.marketdata.options.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/** oipulse "Big OI": the latest bucket's legs ranked by absolute interval OI-change (the movers). */
@Service
public class OiBigOiService {

  public record BigOiRow(
      @Schema(type = "string") BigDecimal strike, String optionType, long oi, long oiChange,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal ltp) {}

  public record BigOi(
      List<BigOiRow> items,
      @Schema(types = {"string", "null"}) OffsetDateTime asOf) {}

  /** Top-{@code topN} legs by {@code |oiChange|}, descending; {@code asOf} = the latest bucket. */
  public BigOi bigOi(List<OptionsSnapshotReader.StrikePoint> latest, int topN) {
    List<BigOiRow> items =
        latest.stream()
            .map(
                p ->
                    new BigOiRow(
                        p.strike(),
                        p.optionType(),
                        p.oi() == null ? 0 : p.oi(),
                        p.oiChange() == null ? 0 : p.oiChange(),
                        p.ltp()))
            .sorted(Comparator.comparingLong((BigOiRow r) -> Math.abs(r.oiChange())).reversed())
            .limit(topN)
            .toList();
    OffsetDateTime asOf = latest.isEmpty() ? null : latest.get(latest.size() - 1).bucket();
    return new BigOi(items, asOf);
  }
}
