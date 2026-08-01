package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.strategysignal.insights.StrategyEvidenceInputs.BoardRow;
import in.arthayantra.strategysignal.insights.StrategyEvidenceInputs.Criterion;
import in.arthayantra.strategysignal.insights.StrategyEvidenceInputs.PriorSnapshot;
import in.arthayantra.strategysignal.paper.GraduationService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Assembles the strategy-evidence + sell-decision reads for the I3 generators and the strategy-dossier
 * endpoint (INT design §5). The one deliberately-justified cross-module read edge in the {@code
 * insights} module: it CONSUMES {@link GraduationService#board()} read-only rather than re-deriving the
 * graduation money-math in SQL (§0 "no re-scoring"; duplicating the drawdown/PF/expectancy walk would be
 * a divergence risk on a money surface). Everything else — the {@code strategy_graduations} marker,
 * {@code sell_decisions}, {@code signal_rejections}, {@code strategies}, and the prior snapshot rows —
 * is a READ-ONLY same-schema {@link JdbcTemplate} view (the {@code BookHeatReader} precedent: SQL is not
 * a Java import). NEVER mutates any of them. Fail-soft: a read hiccup yields an empty scan, never a throw
 * on the nightly sweep path.
 */
@Component
public class StrategyEvidenceReader {

  private static final Logger log = LoggerFactory.getLogger(StrategyEvidenceReader.class);
  private static final int DOSSIER_REJECTION_DAYS = 30;
  private static final int DOSSIER_TOP_RAILS = 8;

  private final GraduationService graduation;
  private final InsightRepository insights;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public StrategyEvidenceReader(
      GraduationService graduation, InsightRepository insights, JdbcTemplate jdbc, Clock clock) {
    this.graduation = graduation;
    this.insights = insights;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Today's board vs yesterday's snapshots + the GRADUATED markers (the 21:10 sweep input). */
  public StrategyEvidenceInputs strategyEvidenceScan() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), Ist.ZONE);
    try {
      List<BoardRow> board =
          graduation.board().strategies().stream().map(StrategyEvidenceReader::toBoardRow).toList();
      Set<UUID> graduated = graduatedMarkers();
      OffsetDateTime before = today.atStartOfDay(Ist.ZONE).toOffsetDateTime();
      Map<UUID, PriorSnapshot> yesterday = new LinkedHashMap<>();
      insights.latestStrategyEvidenceSnapshots(before).forEach((id, ev) -> yesterday.put(id, toPrior(ev)));
      return new StrategyEvidenceInputs(today, board, yesterday, graduated);
    } catch (RuntimeException e) {
      log.debug("insight strategy-evidence scan unavailable: {}", e.getMessage());
      return new StrategyEvidenceInputs(today, List.of(), Map.of(), Set.of());
    }
  }

  /** The durable unacknowledged SELL rows the swing batch wrote (V037) — the sell-decision input. */
  public SellDecisionInputs sellDecisionScan() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), Ist.ZONE);
    try {
      List<SellDecisionInputs.SellRow> sells =
          jdbc.query(
              """
              SELECT id, book, run_date, signal_id, exchange, symbol, setup, verdict, sell_reason,
                     entry_price, current_price, unrealized_pct
              FROM sell_decisions
              WHERE acknowledged_at IS NULL AND verdict LIKE 'SELL%'
              ORDER BY id
              """,
              (rs, i) -> {
                LocalDate runDate = rs.getObject("run_date", LocalDate.class);
                long sessionsOpen = runDate == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(runDate, today));
                return new SellDecisionInputs.SellRow(
                    rs.getLong("id"), rs.getString("book"), runDate, rs.getLong("signal_id"),
                    rs.getString("exchange"), rs.getString("symbol"), rs.getString("setup"),
                    rs.getString("verdict"), rs.getString("sell_reason"), rs.getBigDecimal("entry_price"),
                    rs.getBigDecimal("current_price"), rs.getBigDecimal("unrealized_pct"), sessionsOpen);
              });
      return new SellDecisionInputs(today, sells);
    } catch (RuntimeException e) {
      log.debug("insight sell-decision scan unavailable: {}", e.getMessage());
      return new SellDecisionInputs(today, List.of());
    }
  }

  /**
   * The qualification dossier (§5.1) — the join the owner does by hand across the graduation, signals,
   * sell-decision and insight pages, server-assembled. Assembly-only: every element is an existing
   * read. Backtest refs + the scalper shadow-variant slice are cross-service / cross-module deep-links
   * the FE half hydrates (noted in {@code notes}), not re-fetched here.
   */
  public Dossier dossier(UUID strategyId) {
    String asOf = OffsetDateTime.now(clock).toString();
    Optional<BoardRow> boardRow =
        graduation.board().strategies().stream()
            .map(StrategyEvidenceReader::toBoardRow)
            .filter(r -> r.strategyId().equals(strategyId))
            .findFirst();

    String slug;
    String name;
    boolean enabled;
    String stage;
    List<Criterion> criteria;
    if (boardRow.isPresent()) {
      BoardRow r = boardRow.get();
      slug = r.slug();
      name = r.name();
      enabled = true; // the board is exactly the enabled+published live set
      stage = r.stage();
      criteria = r.criteria();
    } else {
      Map<String, Object> id = identity(strategyId);
      slug = (String) id.getOrDefault("slug", null);
      name = (String) id.getOrDefault("name", null);
      enabled = Boolean.TRUE.equals(id.get("enabled"));
      stage = "UNLISTED"; // disabled or unpublished → not scored by the board
      criteria = List.of();
    }

    OffsetDateTime graduatedAt = graduatedAt(strategyId);
    List<CrossingEntry> timeline =
        insights.list("STRATEGY_EVIDENCE", null, null, "strategy:" + strategyId, null, null, false, 50, 0)
            .stream()
            .map(in -> new CrossingEntry(in.generatedAt(), in.severity(), in.title()))
            .toList();
    List<RailCount> rejectionProfile = slug == null ? List.of() : rejectionProfile(slug);
    List<OpenSell> openSells = slug == null ? List.of() : openSellDecisions(slug);

    List<String> notes = new ArrayList<>();
    notes.add("Backtest refs (/backtests/summary) and the scalper shadow-variant slice are FE deep-links.");
    if (boardRow.isEmpty()) {
      notes.add("Strategy is not in the live graduation board (disabled or unpublished) — criteria omitted.");
    }
    return new Dossier(
        strategyId, slug, name, enabled, stage, criteria, graduatedAt, timeline, rejectionProfile,
        openSells, asOf, List.copyOf(notes));
  }

  // ---- reads ------------------------------------------------------------------------------------

  private Set<UUID> graduatedMarkers() {
    return new java.util.LinkedHashSet<>(
        jdbc.query("SELECT strategy_id FROM strategy_graduations", (rs, i) -> rs.getObject("strategy_id", UUID.class)));
  }

  private OffsetDateTime graduatedAt(UUID strategyId) {
    return jdbc
        .query(
            "SELECT graduated_at FROM strategy_graduations WHERE strategy_id = ?",
            (rs, i) -> rs.getObject("graduated_at", OffsetDateTime.class),
            strategyId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private Map<String, Object> identity(UUID strategyId) {
    return jdbc
        .query(
            "SELECT slug, name, enabled FROM strategies WHERE id = ?",
            (rs, i) -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("slug", rs.getString("slug"));
              m.put("name", rs.getString("name"));
              m.put("enabled", rs.getBoolean("enabled"));
              return m;
            },
            strategyId)
        .stream()
        .findFirst()
        .orElseGet(Map::of);
  }

  private List<RailCount> rejectionProfile(String slug) {
    OffsetDateTime from = OffsetDateTime.now(clock).minusDays(DOSSIER_REJECTION_DAYS);
    return jdbc.query(
        """
        SELECT blocking_rail AS rail, count(*) AS n
        FROM signal_rejections
        WHERE strategy_slug = ? AND generated_at >= ?
        GROUP BY blocking_rail ORDER BY n DESC LIMIT ?
        """,
        (rs, i) -> new RailCount(rs.getString("rail"), rs.getLong("n")),
        slug, from, DOSSIER_TOP_RAILS);
  }

  private List<OpenSell> openSellDecisions(String slug) {
    return jdbc.query(
        """
        SELECT id, run_date, symbol, verdict, unrealized_pct, acknowledged_at
        FROM sell_decisions
        WHERE setup = ? ORDER BY run_date DESC, id DESC LIMIT 50
        """,
        (rs, i) ->
            new OpenSell(
                rs.getLong("id"), rs.getObject("run_date", LocalDate.class), rs.getString("symbol"),
                rs.getString("verdict"), rs.getBigDecimal("unrealized_pct"),
                rs.getObject("acknowledged_at", OffsetDateTime.class) != null),
        slug);
  }

  // ---- mapping ----------------------------------------------------------------------------------

  private static BoardRow toBoardRow(GraduationService.StrategyGraduation s) {
    List<Criterion> criteria =
        s.criteria().stream()
            .map(c -> new Criterion(c.name(), c.required(), c.actual(), c.pass()))
            .toList();
    return new BoardRow(
        s.strategyId(), s.slug(), s.name(), s.stage(), s.trades(), s.profitFactor(), s.expectancy(),
        s.maxDrawdownPct(), criteria);
  }

  /** Parse a prior snapshot insight's evidence back into the pass-map + stage the crossing needs. */
  private static PriorSnapshot toPrior(JsonNode evidence) {
    String stage = null;
    Map<String, Boolean> pass = new LinkedHashMap<>();
    if (evidence != null && evidence.isArray()) {
      for (JsonNode e : evidence) {
        String label = e.path("label").asText("");
        String value = e.path("value").asText("");
        if (StrategyEvidenceGenerator.LABEL_STAGE.equals(label)) {
          stage = value;
        } else if (label.startsWith(StrategyEvidenceGenerator.CRITERION_PREFIX)) {
          pass.put(label.substring(StrategyEvidenceGenerator.CRITERION_PREFIX.length()), "pass".equals(value));
        }
      }
    }
    return new PriorSnapshot(stage, pass);
  }

  // ---- dossier shape ----------------------------------------------------------------------------

  /** The server-assembled qualification dossier (§5.1). Typed record → enumerates into the contract. */
  public record Dossier(
      UUID strategyId,
      @Schema(types = {"string", "null"}) String slug,
      @Schema(types = {"string", "null"}) String name,
      boolean enabled,
      String stage,
      List<Criterion> criteria,
      @Schema(types = {"string", "null"}) OffsetDateTime graduatedAt,
      List<CrossingEntry> crossingTimeline,
      List<RailCount> rejectionProfile,
      List<OpenSell> openSellDecisions,
      String asOf,
      List<String> notes) {}

  /** One threshold-crossing timeline entry (the STRATEGY_EVIDENCE history the board lacks, §5.2). */
  public record CrossingEntry(OffsetDateTime at, String severity, String title) {}

  /**
   * One blocking-rail count in the dossier rejection profile.
   *
   * <p>{@code @Schema(name)} is load-bearing: {@code SignalRejectionRepository.RailCount} shares
   * this simple name, and springdoc would collapse both into ONE spec component (task_1c04803f);
   * the ContractCaptureTest collision assertion fails without the distinct name. The rejections
   * endpoint's twin keeps the plain {@code RailCount} name (the FE contracts bridge binds it).
   */
  @Schema(name = "EvidenceRailCount")
  public record RailCount(String rail, long count) {}

  /** One open sell-decision row in the dossier. */
  public record OpenSell(
      long sellDecisionId, LocalDate runDate, String symbol, String verdict,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal unrealizedPct,
      boolean acknowledged) {}
}
