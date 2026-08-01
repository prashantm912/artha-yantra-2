package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.time.Ist;
import io.swagger.v3.oas.annotations.media.Schema;
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

  /**
   * The Stage-1 fired-vs-rejected contrast for a (version, IST-day): composite + dot-supports.
   *
   * <p>The per-row dot counts are the SCOREABLE population — see {@link DotCounts} for why a dot the
   * scorer withheld is excluded, and why a row written before the {@code absent} flag was serialized
   * is kept out of the aggregate ratios rather than blended into them.
   */
  public FiredVsRejected firedVsRejected(UUID strategyVersionId, LocalDate istDay) {
    OffsetDateTime from = istDay.atStartOfDay(Ist.ZONE).toOffsetDateTime();
    OffsetDateTime to = istDay.plusDays(1).atStartOfDay(Ist.ZONE).toOffsetDateTime();

    List<Scored<FiredRow>> fired =
        jdbc.query(
            """
            SELECT id, tradingsymbol, side, composite_score, scalper_detail::text AS detail, generated_at
            FROM signals
            WHERE strategy_version_id = ? AND generated_at >= ? AND generated_at < ?
            ORDER BY generated_at DESC, id DESC
            """,
            (rs, i) -> {
              DotCounts dots = dotSupports(rs.getString("detail"), "dots");
              return new Scored<>(
                  new FiredRow(
                      rs.getLong("id"), rs.getString("tradingsymbol"), rs.getString("side"),
                      rs.getBigDecimal("composite_score"), dots.supporting(), dots.total(),
                      rs.getObject("generated_at", OffsetDateTime.class)),
                  dots);
            },
            strategyVersionId, from, to);

    List<Scored<RejectedRow>> rejected =
        jdbc.query(
            """
            SELECT id, tradingsymbol, side, composite_score, composite_threshold, blocking_rail,
                   diagnostic::text AS diagnostic, generated_at
            FROM signal_rejections
            WHERE strategy_version_id = ? AND generated_at >= ? AND generated_at < ?
            ORDER BY generated_at DESC, id DESC
            """,
            (rs, i) -> {
              DotCounts dots = confluenceDotSupports(rs.getString("diagnostic"));
              return new Scored<>(
                  new RejectedRow(
                      rs.getLong("id"), rs.getString("tradingsymbol"), rs.getString("side"),
                      rs.getBigDecimal("composite_score"), rs.getBigDecimal("composite_threshold"),
                      rs.getString("blocking_rail"), dots.supporting(), dots.total(),
                      rs.getObject("generated_at", OffsetDateTime.class)),
                  dots);
            },
            strategyVersionId, from, to);

    return new FiredVsRejected(
        fired.stream().map(Scored::row).toList(),
        rejected.stream().map(Scored::row).toList(),
        contrast(fired, rejected));
  }

  private static Contrast contrast(List<Scored<FiredRow>> fired, List<Scored<RejectedRow>> rejected) {
    BigDecimal meanFired = meanComposite(fired.stream().map(f -> f.row().composite()).toList());
    BigDecimal meanRejected = meanComposite(rejected.stream().map(r -> r.row().composite()).toList());
    BigDecimal ratioFired = meanSupportRatio(fired.stream().map(Scored::counts).toList());
    BigDecimal ratioRejected = meanSupportRatio(rejected.stream().map(Scored::counts).toList());
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

  /**
   * The mean per-row support ratio — reported ONLY when EVERY scoreable row on this side is on the
   * current definition, and {@code null} otherwise.
   *
   * <p>A legacy row's {@code total} still counts withheld dots (the superseded definition) and cannot
   * be corrected after the fact: an old {@code supports:false} {@code iv_rank} dot is genuinely
   * indistinguishable between "withheld" and "present and unsupporting". Blending such a row into
   * this mean would step the series at the deploy boundary for no market reason, and the step is NOT
   * invertible from the aggregate, because the mean runs over rows with DIFFERENT dot counts (the
   * optional {@code iv_slope} / {@code iv_abs_band} / {@code premium_skew} / {@code dow} dots are
   * conditionally added, {@code ConnectTheDotsScorer:242-275}).
   *
   * <p><b>Why a MIXED day returns null rather than a mean of just its modern rows.</b> The row lists
   * and {@code firedCount}/{@code rejectedCount} beside this figure cover EVERY row, so a mean over
   * the flag-bearing subset would present a partial sample as the full-day contrast with nothing
   * marking it partial. Worse, that subset is not a random one: on the boundary day the legacy/modern
   * split is exactly "before vs after the restart", a contiguous session-phase slice, so its mean is
   * time-biased rather than merely thin. The invariant is therefore total — <b>if this is non-null it
   * describes exactly the rows returned for that side</b> — and the cost is bounded to a single day,
   * since rows are append-only and every row written after the deploy carries the flag.
   *
   * <p>A row with NO scoreable dots ({@code total == 0} — a null {@code scalper_detail}, a
   * non-scalper strategy, an all-withheld degenerate) is skipped rather than treated as legacy: it
   * carries no ratio to contaminate, and such rows occur on modern days too.
   *
   * <p>The raw per-row {@code dotSupports}/{@code dotTotal} stay on the response either way, so
   * nothing is hidden — only the aggregate refuses to average across two definitions.
   */
  static BigDecimal meanSupportRatio(List<DotCounts> counts) {
    List<BigDecimal> ratios = new ArrayList<>();
    for (DotCounts c : counts) {
      if (c.total() == 0) {
        continue;
      }
      if (!c.absentFlagged()) {
        return null;
      }
      ratios.add(
          BigDecimal.valueOf(c.supporting()).divide(BigDecimal.valueOf(c.total()), 4, RoundingMode.HALF_UP));
    }
    if (ratios.isEmpty()) {
      return null;
    }
    return ratios.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(ratios.size()), 4, RoundingMode.HALF_UP);
  }

  /** Dot counts under a top-level array field ({@code scalper_detail.dots}). */
  private DotCounts dotSupports(String json, String arrayField) {
    if (json == null) {
      return DotCounts.EMPTY;
    }
    try {
      JsonNode node = objectMapper.readTree(json).path(arrayField);
      return countSupports(node);
    } catch (Exception e) {
      return DotCounts.EMPTY;
    }
  }

  /** Dot counts under {@code diagnostic.confluence.dots}. */
  private DotCounts confluenceDotSupports(String json) {
    if (json == null) {
      return DotCounts.EMPTY;
    }
    try {
      JsonNode node = objectMapper.readTree(json).path("confluence").path("dots");
      return countSupports(node);
    } catch (Exception e) {
      return DotCounts.EMPTY;
    }
  }

  /** Package-private + static (no instance state) so the counting rule is directly testable. */
  static DotCounts countSupports(JsonNode dots) {
    if (dots == null || !dots.isArray()) {
      return DotCounts.EMPTY;
    }
    int supporting = 0;
    int total = 0;
    boolean flagged = false;
    for (JsonNode d : dots) {
      // All three serializers write `absent` unconditionally on EVERY dot, so the key is on every
      // dot of a modern row and on none of a legacy one — one dot carrying it settles the row.
      flagged |= d.has("absent");
      if (d.path("absent").asBoolean(false)) {
        // Withheld: out of BOTH counts, mirroring ConnectTheDotsScorer.score (:284-292). Skipping it
        // from the numerator too is a no-op today (the one absent-capable dot, `iv_rank`, is built
        // `!ivRankAbsent && ...` so absent always reads supports=false) but it is the rule, not a
        // coincidence, and it holds if a future absent dot is ever constructed differently.
        continue;
      }
      total++;
      if (d.path("supports").asBoolean(false)) {
        supporting++;
      }
    }
    return new DotCounts(supporting, total, flagged);
  }

  /**
   * One row's dot counts plus the PROVENANCE the response row cannot carry.
   *
   * <p>{@code total} is the SCOREABLE dot population: dots flagged {@code absent:true} are excluded,
   * matching {@code ConnectTheDotsScorer.score} (:284-292), which withholds a missing-input dot from
   * BOTH its numerator and its denominator so a data gap is never scored as evidence against the
   * side. Counting them — the behaviour this replaces — reported e.g. 17/18 where the scorer's
   * population was 17, i.e. the reader charged the side for a dot the scorer had refused to charge
   * it for. Today exactly one dot can be absent: {@code iv_rank}, whose withholding condition is
   * {@code ivRankNull || !ivRankDot} ({@code ConnectTheDotsScorer:231-234}) — the IV-history rank is
   * unavailable ({@code MarketOiClient:517-522} supplies one only past the 60-trading-day floor), OR
   * the {@code iv-rank-dot} tag is unarmed, which is the DEFAULT since #1179 deliberately stopped
   * the maturing floor from self-arming the dot on a calendar trigger.
   *
   * <p>{@code absentFlagged} says whether the row was written after the flag began being serialized.
   * A row written before it has no {@code absent} key at all, so {@code path("absent")} reads every
   * dot as present and the count silently keeps the OLD meaning — absentness is simply unknowable
   * there. Recovering it from the {@code reason} prose was considered and rejected: {@code reason} is
   * serialized on the two DIAGNOSTIC shapes only, never on {@code scalper_detail}, which is what the
   * FIRED side of this contrast reads — it would have corrected half the comparison.
   */
  record DotCounts(int supporting, int total, boolean absentFlagged) {
    static final DotCounts EMPTY = new DotCounts(0, 0, false);
  }

  /** A response row paired with the counts behind it — the counts carry provenance the row does not. */
  private record Scored<T>(T row, DotCounts counts) {}

  /** The Stage-1 contrast bundle (INT design §4.2 — composite + dot-supports, per §13 row 19 Stage 1). */
  public record FiredVsRejected(List<FiredRow> fired, List<RejectedRow> rejected, Contrast contrast) {}

  /**
   * One fired signal's Stage-1 row. {@code dotTotal} is the SCOREABLE dot population (withheld dots
   * excluded), which on a row predating the {@code absent} flag falls back to the full dot count —
   * see {@link DotCounts}.
   */
  public record FiredRow(
      long signalId, String tradingsymbol, String side, BigDecimal composite,
      int dotSupports, int dotTotal, OffsetDateTime generatedAt) {}

  /**
   * One rejected signal's Stage-1 row (adds the blocking rail + composite threshold). {@code
   * dotTotal} carries the same scoreable-population meaning as {@link FiredRow#dotTotal()}.
   */
  public record RejectedRow(
      long rejectionId, String tradingsymbol,
      @Schema(types = {"string", "null"}) String side,
      @Schema(types = {"number", "null"}) BigDecimal composite,
      @Schema(types = {"number", "null"}) BigDecimal threshold,
      String blockingRail, int dotSupports, int dotTotal, OffsetDateTime generatedAt) {}

  /**
   * The fired-vs-rejected summary contrast. A {@code meanSupportRatio*} field is non-null only when
   * EVERY scoreable row on that side is on the current dot-counting definition — see {@link
   * #meanSupportRatio}. A day containing any row that predates the {@code absent} flag reports
   * {@code null} for that side rather than a number on the superseded definition or a mean over
   * only part of the day. The {@code *Count} fields and the row lists always cover every row.
   */
  public record Contrast(
      int firedCount, int rejectedCount,
      @Schema(types = {"number", "null"}) BigDecimal meanCompositeFired,
      @Schema(types = {"number", "null"}) BigDecimal meanCompositeRejected,
      @Schema(types = {"number", "null"}) BigDecimal meanSupportRatioFired,
      @Schema(types = {"number", "null"}) BigDecimal meanSupportRatioRejected) {}
}
