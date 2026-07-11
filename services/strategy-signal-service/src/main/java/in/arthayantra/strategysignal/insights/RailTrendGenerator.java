package in.arthayantra.strategysignal.insights;

import in.arthayantra.common.web.time.Ist;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * REJECTION_RAIL_TREND (INT design §4.2): on the EOD sweep, flag a rail whose block share today
 * spiked vs its trailing-session mean — a rail that suddenly dominates is either gate drift or a data
 * defect worth a look. PURE over the parsed {@link RejectionScan} rail-trend list ({@link
 * RejectionReader} accrued the per-session shares). One insight per (rail, IST-day); a spike above 3×
 * escalates to WARN.
 */
@Component
public class RailTrendGenerator implements InsightGenerator {

  private static final BigDecimal WARN_RATIO = new BigDecimal("3.0");

  private final InsightProperties.Rejection cfg;

  public RailTrendGenerator(InsightProperties props) {
    this.cfg = props.rejection();
  }

  @Override
  public InsightType type() {
    return InsightType.REJECTION_RAIL_TREND;
  }

  @Override
  public List<InsightCandidate> generate(GenerationContext ctx) {
    RejectionScan scan = ctx.rejections();
    if (scan == null || scan.railTrend() == null || scan.railTrend().isEmpty()) {
      return List.of();
    }
    LocalDate istDay = ctx.now().atZoneSameInstant(Ist.ZONE).toLocalDate();
    List<InsightCandidate> out = new ArrayList<>();
    for (RejectionScan.RailShare rs : scan.railTrend()) {
      if (rs.todayCount() < cfg.railTrendMinCount()) {
        continue;
      }
      if (rs.ratio() == null || rs.ratio().compareTo(cfg.railTrendRatio()) < 0) {
        continue;
      }
      Severity severity = rs.ratio().compareTo(WARN_RATIO) >= 0 ? Severity.WARN : Severity.NOTICE;
      out.add(
          new InsightCandidate(
              InsightType.REJECTION_RAIL_TREND,
              severity,
              "market",
              rs.rail() + " blocked " + pct(rs.todayShare()) + " today vs " + pct(rs.meanShare())
                  + " " + rs.sessions() + "-day avg",
              rs.rail() + " accounted for " + pct(rs.todayShare()) + " of today's " + rs.todayTotal()
                  + " rejections (" + rs.todayCount() + " rows) vs a " + rs.sessions() + "-session mean of "
                  + pct(rs.meanShare()) + " — a " + plain(rs.ratio()) + "× spike. Check the rail for gate"
                  + " drift or a data defect.",
              List.of(
                  Evidence.sourced("today share", pct(rs.todayShare()) + " (" + rs.todayCount() + "/"
                      + rs.todayTotal() + ")", "/api/v1/signal-rejections/rail-counts", ctx.now().toString()),
                  Evidence.of(rs.sessions() + "-day mean share", pct(rs.meanShare())),
                  Evidence.of("spike ratio", plain(rs.ratio()) + "×")),
              null, null, DataTrust.OK, List.of(),
              "REJECTION_RAIL_TREND:" + rs.rail() + ":" + istDay, null, null, false));
    }
    return out;
  }

  private static String pct(BigDecimal fraction) {
    return fraction == null
        ? "n/a"
        : fraction.multiply(BigDecimal.valueOf(100))
            .setScale(1, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString() + "%";
  }

  private static String plain(BigDecimal v) {
    return v == null ? "n/a" : v.stripTrailingZeros().toPlainString();
  }
}
