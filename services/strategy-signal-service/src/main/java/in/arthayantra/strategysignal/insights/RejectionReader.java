package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.time.Ist;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Assembles the rejection-intelligence reads for the REJECTION_NEARMISS + REJECTION_RAIL_TREND
 * generators and the fired-vs-rejected Stage-1 endpoint (INT design §4.2) — a READ-ONLY view over
 * the existing {@code signal_rejections} + {@code signals} tables via {@link JdbcTemplate}. It does
 * NOT import the {@code signals} module (the BookHeatReader precedent: same-schema SQL is not a Java
 * import, so no Modulith cycle). Fail-soft: any read error yields an empty result / bundle, never a
 * throw on the sweep path.
 */
@Component
public class RejectionReader {

  private static final Logger log = LoggerFactory.getLogger(RejectionReader.class);

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final InsightProperties.Rejection cfg;

  public RejectionReader(
      JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock, InsightProperties props) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.cfg = props.rejection();
  }

  /** The near-miss window for the 15-min sweep: recent numeric-rail rejections ranked by closeness. */
  public RejectionScan nearMissScan() {
    try {
      OffsetDateTime from = OffsetDateTime.now(clock).minusMinutes(cfg.nearMissWindowMinutes());
      List<RejectionScan.NearMiss> near =
          jdbc.query(
              """
              SELECT id, strategy_slug, tradingsymbol, side, blocking_rail,
                     blocking_operand, blocking_threshold, blocking_margin
              FROM signal_rejections
              WHERE generated_at >= ?
                AND blocking_operand IS NOT NULL AND blocking_threshold IS NOT NULL
                AND blocking_threshold <> 0 AND blocking_margin IS NOT NULL
              """,
              (rs, i) -> {
                BigDecimal margin = rs.getBigDecimal("blocking_margin");
                BigDecimal threshold = rs.getBigDecimal("blocking_threshold");
                BigDecimal closeness =
                    margin.abs().divide(threshold.abs(), 4, RoundingMode.HALF_UP);
                return new RejectionScan.NearMiss(
                    rs.getLong("id"), rs.getString("strategy_slug"), rs.getString("tradingsymbol"),
                    rs.getString("side"), rs.getString("blocking_rail"),
                    rs.getBigDecimal("blocking_operand"), threshold, margin, closeness);
              },
              from);
      near.sort(Comparator.comparing(RejectionScan.NearMiss::closeness));
      return new RejectionScan(near, List.of());
    } catch (RuntimeException e) {
      log.debug("insight near-miss read unavailable: {}", e.getMessage());
      return new RejectionScan(List.of(), List.of());
    }
  }

  /**
   * The rail-trend accrual for the EOD sweep: today's per-rail block share vs the trailing-session
   * mean share. Reads per-(rail, IST-session-date) counts over the window and aggregates in Java.
   */
  public RejectionScan railTrendScan() {
    try {
      LocalDate today = LocalDate.ofInstant(clock.instant(), Ist.ZONE);
      OffsetDateTime from =
          today.minusDays(cfg.railTrendSessions() + 7L).atStartOfDay(Ist.ZONE).toOffsetDateTime();
      // (rail, ist-date) -> count
      Map<String, Map<LocalDate, Long>> byRail = new LinkedHashMap<>();
      Map<LocalDate, Long> totalPerDay = new LinkedHashMap<>();
      jdbc.query(
          """
          SELECT blocking_rail AS rail,
                 (generated_at AT TIME ZONE 'Asia/Kolkata')::date AS d,
                 count(*) AS n
          FROM signal_rejections
          WHERE generated_at >= ?
          GROUP BY blocking_rail, (generated_at AT TIME ZONE 'Asia/Kolkata')::date
          """,
          rs -> {
            String rail = rs.getString("rail");
            LocalDate d = rs.getObject("d", LocalDate.class);
            long n = rs.getLong("n");
            byRail.computeIfAbsent(rail, k -> new LinkedHashMap<>()).merge(d, n, Long::sum);
            totalPerDay.merge(d, n, Long::sum);
          },
          from);
      long todayTotal = totalPerDay.getOrDefault(today, 0L);
      if (todayTotal == 0) {
        return new RejectionScan(List.of(), List.of());
      }
      List<RejectionScan.RailShare> shares = new ArrayList<>();
      for (Map.Entry<String, Map<LocalDate, Long>> e : byRail.entrySet()) {
        Map<LocalDate, Long> perDay = e.getValue();
        long todayCount = perDay.getOrDefault(today, 0L);
        if (todayCount == 0) {
          continue;
        }
        BigDecimal todayShare =
            BigDecimal.valueOf(todayCount).divide(BigDecimal.valueOf(todayTotal), 4, RoundingMode.HALF_UP);
        // Mean daily share over the prior sessions that HAD rejections (exclude today + empty days).
        List<BigDecimal> priorShares = new ArrayList<>();
        for (Map.Entry<LocalDate, Long> day : perDay.entrySet()) {
          if (day.getKey().equals(today)) {
            continue;
          }
          long dayTotal = totalPerDay.getOrDefault(day.getKey(), 0L);
          if (dayTotal > 0) {
            priorShares.add(
                BigDecimal.valueOf(day.getValue()).divide(BigDecimal.valueOf(dayTotal), 4, RoundingMode.HALF_UP));
          }
        }
        BigDecimal meanShare =
            priorShares.isEmpty()
                ? BigDecimal.ZERO
                : priorShares.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(priorShares.size()), 4, RoundingMode.HALF_UP);
        BigDecimal ratio =
            meanShare.signum() == 0 ? null : todayShare.divide(meanShare, 2, RoundingMode.HALF_UP);
        shares.add(
            new RejectionScan.RailShare(
                e.getKey(), todayCount, todayTotal, todayShare, meanShare, ratio, cfg.railTrendSessions()));
      }
      shares.sort(Comparator.comparing((RejectionScan.RailShare r) -> r.todayShare()).reversed());
      return new RejectionScan(List.of(), shares);
    } catch (RuntimeException e) {
      log.debug("insight rail-trend read unavailable: {}", e.getMessage());
      return new RejectionScan(List.of(), List.of());
    }
  }

  /** The Stage-1 fired-vs-rejected contrast for a (version, IST-day): composite + dot-supports. */
  public FiredVsRejected firedVsRejected(UUID strategyVersionId, LocalDate istDay) {
    OffsetDateTime from = istDay.atStartOfDay(Ist.ZONE).toOffsetDateTime();
    OffsetDateTime to = istDay.plusDays(1).atStartOfDay(Ist.ZONE).toOffsetDateTime();

    List<FiredRow> fired =
        jdbc.query(
            """
            SELECT id, tradingsymbol, side, composite_score, scalper_detail::text AS detail, generated_at
            FROM signals
            WHERE strategy_version_id = ? AND generated_at >= ? AND generated_at < ?
            ORDER BY generated_at DESC, id DESC
            """,
            (rs, i) -> {
              int[] dots = dotSupports(rs.getString("detail"), "dots");
              return new FiredRow(
                  rs.getLong("id"), rs.getString("tradingsymbol"), rs.getString("side"),
                  rs.getBigDecimal("composite_score"), dots[0], dots[1],
                  rs.getObject("generated_at", OffsetDateTime.class));
            },
            strategyVersionId, from, to);

    List<RejectedRow> rejected =
        jdbc.query(
            """
            SELECT id, tradingsymbol, side, composite_score, composite_threshold, blocking_rail,
                   diagnostic::text AS diagnostic, generated_at
            FROM signal_rejections
            WHERE strategy_version_id = ? AND generated_at >= ? AND generated_at < ?
            ORDER BY generated_at DESC, id DESC
            """,
            (rs, i) -> {
              int[] dots = confluenceDotSupports(rs.getString("diagnostic"));
              return new RejectedRow(
                  rs.getLong("id"), rs.getString("tradingsymbol"), rs.getString("side"),
                  rs.getBigDecimal("composite_score"), rs.getBigDecimal("composite_threshold"),
                  rs.getString("blocking_rail"), dots[0], dots[1],
                  rs.getObject("generated_at", OffsetDateTime.class));
            },
            strategyVersionId, from, to);

    return new FiredVsRejected(fired, rejected, contrast(fired, rejected));
  }

  private static Contrast contrast(List<FiredRow> fired, List<RejectedRow> rejected) {
    BigDecimal meanFired = meanComposite(fired.stream().map(FiredRow::composite).toList());
    BigDecimal meanRejected = meanComposite(rejected.stream().map(RejectedRow::composite).toList());
    BigDecimal ratioFired = meanSupportRatio(fired.stream().map(f -> new int[] {f.dotSupports(), f.dotTotal()}).toList());
    BigDecimal ratioRejected =
        meanSupportRatio(rejected.stream().map(r -> new int[] {r.dotSupports(), r.dotTotal()}).toList());
    return new Contrast(
        fired.size(), rejected.size(), meanFired, meanRejected, ratioFired, ratioRejected);
  }

  private static BigDecimal meanComposite(List<BigDecimal> values) {
    List<BigDecimal> present = values.stream().filter(v -> v != null).toList();
    if (present.isEmpty()) {
      return null;
    }
    return present.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(present.size()), 4, RoundingMode.HALF_UP);
  }

  private static BigDecimal meanSupportRatio(List<int[]> dots) {
    List<BigDecimal> ratios = new ArrayList<>();
    for (int[] d : dots) {
      if (d[1] > 0) {
        ratios.add(BigDecimal.valueOf(d[0]).divide(BigDecimal.valueOf(d[1]), 4, RoundingMode.HALF_UP));
      }
    }
    if (ratios.isEmpty()) {
      return null;
    }
    return ratios.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(ratios.size()), 4, RoundingMode.HALF_UP);
  }

  /** [supporting, total] dot counts under a top-level array field ({@code scalper_detail.dots}). */
  private int[] dotSupports(String json, String arrayField) {
    if (json == null) {
      return new int[] {0, 0};
    }
    try {
      JsonNode node = objectMapper.readTree(json).path(arrayField);
      return countSupports(node);
    } catch (Exception e) {
      return new int[] {0, 0};
    }
  }

  /** [supporting, total] dot counts under {@code diagnostic.confluence.dots}. */
  private int[] confluenceDotSupports(String json) {
    if (json == null) {
      return new int[] {0, 0};
    }
    try {
      JsonNode node = objectMapper.readTree(json).path("confluence").path("dots");
      return countSupports(node);
    } catch (Exception e) {
      return new int[] {0, 0};
    }
  }

  private static int[] countSupports(JsonNode dots) {
    if (dots == null || !dots.isArray()) {
      return new int[] {0, 0};
    }
    int supporting = 0;
    int total = 0;
    for (JsonNode d : dots) {
      total++;
      if (d.path("supports").asBoolean(false)) {
        supporting++;
      }
    }
    return new int[] {supporting, total};
  }

  /** The Stage-1 contrast bundle (INT design §4.2 — composite + dot-supports, per §13 row 19 Stage 1). */
  public record FiredVsRejected(List<FiredRow> fired, List<RejectedRow> rejected, Contrast contrast) {}

  /** One fired signal's Stage-1 row. */
  public record FiredRow(
      long signalId, String tradingsymbol, String side, BigDecimal composite,
      int dotSupports, int dotTotal, OffsetDateTime generatedAt) {}

  /** One rejected signal's Stage-1 row (adds the blocking rail + composite threshold). */
  public record RejectedRow(
      long rejectionId, String tradingsymbol, String side, BigDecimal composite, BigDecimal threshold,
      String blockingRail, int dotSupports, int dotTotal, OffsetDateTime generatedAt) {}

  /** The fired-vs-rejected summary contrast. */
  public record Contrast(
      int firedCount, int rejectedCount, BigDecimal meanCompositeFired, BigDecimal meanCompositeRejected,
      BigDecimal meanSupportRatioFired, BigDecimal meanSupportRatioRejected) {}
}
