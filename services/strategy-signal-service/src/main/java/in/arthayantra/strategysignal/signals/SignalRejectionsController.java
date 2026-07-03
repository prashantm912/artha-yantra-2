package in.arthayantra.strategysignal.signals;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read surface for the confluence-gate REJECTION diagnostics: every scalper chart-entry the live
 * §12.3 gate blocked, with the first failing rail + margin, the full dot-by-dot confluence, and the
 * raw OI/macro/chart context. Powers the "Rejections" analysis page. Live-only data (no rows on
 * backtest). Returns {@code Map<String,Object>} so response keys never drift the OpenAPI spec.
 */
@RestController
@RequestMapping("/api/v1/signal-rejections")
public class SignalRejectionsController {

  private final SignalRejectionRepository repository;
  private final ShadowPositionRepository shadows;

  /** Wires the repositories. */
  public SignalRejectionsController(
      SignalRejectionRepository repository, ShadowPositionRepository shadows) {
    this.repository = repository;
    this.shadows = shadows;
  }

  /** Paged/filtered rejection history, newest first. */
  @GetMapping
  public Map<String, Object> list(
      @RequestParam(required = false) UUID strategyVersionId,
      @RequestParam(required = false) String rail,
      @RequestParam(required = false) String exchange,
      @RequestParam(required = false) String tradingsymbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int boundedLimit = Math.min(Math.max(limit, 1), 500);
    int boundedOffset = Math.max(offset, 0);
    List<SignalRejectionRepository.RejectionRow> items =
        repository.list(
            strategyVersionId, rail, exchange, tradingsymbol, from, to, boundedLimit, boundedOffset);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("items", items.stream().map(SignalRejectionsController::dto).toList());
    response.put("limit", boundedLimit);
    response.put("offset", boundedOffset);
    return response;
  }

  /** The per-rail block rollup (which condition blocks most) over an optional window. */
  @GetMapping("/rail-counts")
  public Map<String, Object> railCounts(
      @RequestParam(required = false) UUID strategyVersionId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to) {
    List<SignalRejectionRepository.RailCount> counts =
        repository.railCounts(strategyVersionId, from, to);
    return Map.of(
        "items",
        counts.stream()
            .map(
                c -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("rail", c.rail());
                  m.put("count", c.count());
                  return m;
                })
            .toList());
  }

  /** The typed shadow-league envelope (the Map-return ratchet forbids new Map endpoints). */
  public record ShadowSummaryResponse(List<ShadowPositionRepository.VariantSummary> items) {}

  /**
   * The shadow-book league table (roadmap F1): per-variant open/closed/wins/losses + realized
   * points over an optional opened-at window — champion vs challenger configs on identical data.
   */
  @GetMapping("/shadow-summary")
  public ShadowSummaryResponse shadowSummary(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to) {
    return new ShadowSummaryResponse(shadows.variantSummary(from, to));
  }

  private static Map<String, Object> dto(SignalRejectionRepository.RejectionRow row) {
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("id", row.id());
    dto.put("strategyVersionId", row.strategyVersionId());
    dto.put("strategySlug", row.strategySlug());
    dto.put("exchange", row.exchange());
    dto.put("tradingsymbol", row.tradingsymbol());
    dto.put("interval", row.interval());
    dto.put("side", row.side());
    dto.put("blockingRail", row.blockingRail());
    dto.put("blockingOperand", row.blockingOperand());
    dto.put("blockingThreshold", row.blockingThreshold());
    dto.put("blockingMargin", row.blockingMargin());
    dto.put("blockingReason", row.blockingReason());
    dto.put("compositeScore", row.compositeScore());
    dto.put("compositeThreshold", row.compositeThreshold());
    dto.put("diagnostic", row.diagnostic());
    dto.put("barTime", row.barTime());
    dto.put("generatedAt", row.generatedAt());
    return dto;
  }
}
