package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.time.Ist;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Assembles the candidate-trade compare (INT design §4.1) — one server-side typed record per signal so
 * the owner can decide BETWEEN setups. A READ-ONLY view over the existing {@code signals} rows +
 * this layer's SIGNAL_PRIORITY insights (the {@code RejectionReader} SQL precedent — no {@code signals}
 * Java import, no re-scoring): it re-uses the priority the engine already computed, never recomputes it.
 *
 * <p>Comparability guards mirror P1-§2.7's lesson: 2–6 signals (the backtest-compare cap), all from the
 * SAME IST session (else 422, D8 envelope), and a {@code booksDiffer} banner flag when the families are
 * mixed. Margin is a stated "unpriced" ride-through (the {@code POST /market/margin} cross-service call
 * is the FE half's; this backend keeps the column honest rather than fabricating a number).
 */
@Component
public class SignalCompareReader {

  private static final int MIN = 2;
  private static final int MAX = 6;

  private final JdbcTemplate jdbc;
  private final InsightRepository insights;
  private final ObjectMapper objectMapper;

  public SignalCompareReader(JdbcTemplate jdbc, InsightRepository insights, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.insights = insights;
    this.objectMapper = objectMapper;
  }

  /**
   * The ticket prefill for a signal (the OPEN_TICKET / TAKE_SIGNAL PROPOSE, §11.4) — the TRADEABLE leg,
   * the lot-aligned {@code suggested_qty}, and the SL/TP the owner's browser reviews before posting.
   * Empty when the signal is unknown. NEVER places anything — it only reads the signal.
   */
  public Optional<TicketPrefill> ticketPrefill(long signalId) {
    return jdbc
        .query(
            "SELECT tradingsymbol, tradeable_tradingsymbol, tradeable_exchange, exchange, side,"
                + " suggested_qty, stop_loss, target FROM signals WHERE id = ?",
            (rs, i) -> {
              String tsym =
                  rs.getString("tradeable_tradingsymbol") != null
                      ? rs.getString("tradeable_tradingsymbol")
                      : rs.getString("tradingsymbol");
              String exch =
                  rs.getString("tradeable_exchange") != null
                      ? rs.getString("tradeable_exchange")
                      : rs.getString("exchange");
              BigDecimal q = rs.getBigDecimal("suggested_qty");
              return new TicketPrefill(
                  signalId, exch, tsym, rs.getString("side"), q == null ? null : q.longValue(),
                  rs.getBigDecimal("stop_loss"), rs.getBigDecimal("target"));
            },
            signalId)
        .stream()
        .findFirst();
  }

  /** Assemble the compare matrix for 2–6 signal ids (§4.1). Throws 422 on a comparability violation. */
  public CompareResult compare(List<Long> signalIds) {
    List<Long> ids = signalIds == null ? List.of() : signalIds.stream().distinct().toList();
    if (ids.size() < MIN || ids.size() > MAX) {
      throw new ApiException(
          422,
          ErrorCodes.VALIDATION_FAILED,
          "compare needs between " + MIN + " and " + MAX + " distinct signal ids (got " + ids.size() + ")");
    }
    List<SignalSnap> found = readSignals(ids);
    if (found.size() < MIN) {
      throw new ApiException(
          422,
          ErrorCodes.VALIDATION_FAILED,
          "compare needs at least " + MIN + " existing signals (found " + found.size() + ")",
          Map.of("requested", ids, "found", found.stream().map(SignalSnap::id).toList()));
    }
    // Comparability: all from the same IST session (P1-§2.7 — never compare across sessions).
    Set<LocalDate> sessions = new LinkedHashSet<>();
    found.forEach(s -> sessions.add(s.generatedAt().atZoneSameInstant(Ist.ZONE).toLocalDate()));
    if (sessions.size() > 1) {
      throw new ApiException(
          422,
          ErrorCodes.VALIDATION_FAILED,
          "cannot compare signals from different sessions",
          Map.of("sessions", sessions.stream().map(LocalDate::toString).toList()));
    }
    LocalDate session = sessions.iterator().next();

    List<CompareColumn> columns = found.stream().map(this::column).toList();
    boolean booksDiffer =
        columns.stream().map(CompareColumn::family).distinct().count() > 1;
    String differsMost = maxSpreadComponent(columns);

    List<String> notes = new ArrayList<>();
    List<Long> missing = ids.stream().filter(id -> found.stream().noneMatch(s -> s.id() == id)).toList();
    if (!missing.isEmpty()) {
      notes.add("Signals not found (omitted): " + missing);
    }
    notes.add("Margin is 'unpriced' here — the POST /market/margin estimate is the FE column.");
    return new CompareResult(session, booksDiffer, differsMost, columns, List.copyOf(notes));
  }

  private CompareColumn column(SignalSnap s) {
    Optional<Insight> priority =
        insights.list("SIGNAL_PRIORITY", null, null, "signal:" + s.id(), null, null, true, 1, 0)
            .stream()
            .findFirst();
    String family = s.scalperDetail() != null && !s.scalperDetail().isNull() ? "scalper" : "swing";

    BigDecimal priorityScore = priority.map(Insight::priority).orElse(null);
    String band = priority.map(p -> band(p.priorityDetail())).orElse(null);
    List<ComponentPoint> components = priority.map(p -> components(p.priorityDetail())).orElse(List.of());
    String dataTrust = priority.map(Insight::dataTrust).orElse("OK");
    List<String> trustReasons = priority.map(Insight::trustReasons).orElse(List.of());
    String scored =
        priority.isEmpty()
            ? "NO_INSIGHT"
            : ("BLOCKED".equals(dataTrust) || priorityScore == null ? "BLOCKED" : "SCORED");

    BigDecimal optionLegCost = optionLegCost(s);
    BigDecimal riskReward = riskReward(s);

    return new CompareColumn(
        s.id(), s.tradingsymbol(), s.side(), family, family, priorityScore, band, components,
        optionLegCost, "unpriced", riskReward, s.entryPrice(), s.stopLoss(), s.target(), dataTrust,
        trustReasons, scored);
  }

  /** option_ltp (persisted V009 side-channel key) × suggested_qty; null when not a priced scalper leg. */
  private static BigDecimal optionLegCost(SignalSnap s) {
    if (s.scalperDetail() == null || s.scalperDetail().isNull() || s.suggestedQty() == null) {
      return null;
    }
    JsonNode ltp = s.scalperDetail().path("option_ltp");
    if (ltp.isMissingNode() || ltp.isNull()) {
      return null;
    }
    try {
      BigDecimal price = ltp.isNumber() ? ltp.decimalValue() : new BigDecimal(ltp.asText());
      return price.multiply(s.suggestedQty()).setScale(2, RoundingMode.HALF_UP);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** R:R = |target − entry| / |entry − stop|; null when a bracket leg is missing or risk is zero. */
  private static BigDecimal riskReward(SignalSnap s) {
    if (s.entryPrice() == null || s.stopLoss() == null || s.target() == null) {
      return null;
    }
    BigDecimal risk = s.entryPrice().subtract(s.stopLoss()).abs();
    if (risk.signum() == 0) {
      return null;
    }
    BigDecimal reward = s.target().subtract(s.entryPrice()).abs();
    return reward.divide(risk, 2, RoundingMode.HALF_UP);
  }

  private static List<ComponentPoint> components(JsonNode priorityDetail) {
    if (priorityDetail == null || !priorityDetail.path("components").isArray()) {
      return List.of();
    }
    List<ComponentPoint> out = new ArrayList<>();
    for (JsonNode c : priorityDetail.path("components")) {
      out.add(
          new ComponentPoint(
              c.path("key").asText(),
              decimal(c.path("points")),
              decimal(c.path("c"))));
    }
    return out;
  }

  private static String band(JsonNode priorityDetail) {
    if (priorityDetail == null) {
      return null;
    }
    JsonNode band = priorityDetail.path("band");
    return band.isMissingNode() || band.isNull() ? null : band.asText();
  }

  /** The component whose points spread most across the set — "these differ mainly on X" (§4.1). */
  private static String maxSpreadComponent(List<CompareColumn> columns) {
    Map<String, BigDecimal> min = new java.util.LinkedHashMap<>();
    Map<String, BigDecimal> max = new java.util.LinkedHashMap<>();
    for (CompareColumn col : columns) {
      for (ComponentPoint cp : col.components()) {
        if (cp.points() == null) {
          continue;
        }
        min.merge(cp.key(), cp.points(), BigDecimal::min);
        max.merge(cp.key(), cp.points(), BigDecimal::max);
      }
    }
    String best = null;
    BigDecimal bestSpread = BigDecimal.valueOf(-1);
    for (String key : min.keySet()) {
      BigDecimal spread = max.get(key).subtract(min.get(key));
      if (spread.compareTo(bestSpread) > 0) {
        bestSpread = spread;
        best = key;
      }
    }
    return best;
  }

  private List<SignalSnap> readSignals(List<Long> ids) {
    String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
    return jdbc.query(
        "SELECT id, tradingsymbol, side, entry_price, stop_loss, target, suggested_qty,"
            + " scalper_detail, generated_at FROM signals WHERE id IN (" + placeholders + ")",
        (rs, i) ->
            new SignalSnap(
                rs.getLong("id"), rs.getString("tradingsymbol"), rs.getString("side"),
                rs.getBigDecimal("entry_price"), rs.getBigDecimal("stop_loss"), rs.getBigDecimal("target"),
                rs.getBigDecimal("suggested_qty"), readTree(rs.getString("scalper_detail")),
                rs.getObject("generated_at", OffsetDateTime.class)),
        ids.toArray());
  }

  private JsonNode readTree(String json) {
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readTree(json);
    } catch (Exception e) {
      return null;
    }
  }

  private static BigDecimal decimal(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    return node.isNumber() ? node.decimalValue() : parse(node.asText());
  }

  private static BigDecimal parse(String s) {
    try {
      return new BigDecimal(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  // ---- shapes -----------------------------------------------------------------------------------

  private record SignalSnap(
      long id, String tradingsymbol, String side, BigDecimal entryPrice, BigDecimal stopLoss,
      BigDecimal target, BigDecimal suggestedQty, JsonNode scalperDetail, OffsetDateTime generatedAt) {}

  /** The compare matrix (INT design §4.1 / §8.5). Typed record → enumerates into the contract. */
  public record CompareResult(
      LocalDate session,
      boolean booksDiffer,
      @Schema(types = {"string", "null"}) String differsMost,
      List<CompareColumn> columns,
      List<String> notes) {}

  /** One signal's compare column. {@code scored}: SCORED | BLOCKED | NO_INSIGHT. */
  public record CompareColumn(
      long signalId,
      String tradingsymbol,
      String side,
      String family,
      String book,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal priority,
      @Schema(types = {"string", "null"}) String band,
      List<ComponentPoint> components,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal optionLegCost,
      String marginEstimate,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal riskReward,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal entryPrice,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal stopLoss,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal target,
      String dataTrust,
      List<String> trustReasons,
      String scored) {}

  /** One priority component's contribution in a compare column (§3.4 render). */
  public record ComponentPoint(
      String key,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal points,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal c) {}

  /**
   * A ticket prefill (§11.4) — the owner's browser posts {@code {signalId, qty}} to the existing
   * endpoint (POST /paper/orders resolves the book + brackets from the signal; POST /signals/{id}/taken
   * opens from the signal). The leg/side/qty/SL/TP are here for the review render.
   */
  public record TicketPrefill(
      long signalId, String exchange, String tradingsymbol, String side,
      @Schema(types = {"integer", "null"}) Long qty,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal stopLoss,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal target) {}
}
