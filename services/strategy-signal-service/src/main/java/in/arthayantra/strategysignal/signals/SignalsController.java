package in.arthayantra.strategysignal.signals;

import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The C-2.14 signal surface: history, active calls, reasoning detail, taken/dismiss. */
@RestController
@RequestMapping("/api/v1/signals")
public class SignalsController {

  /** Optional taken metadata. */
  public record TakenRequest(String fillPrice, Integer qty, String note) {}

  private final SignalRepository repository;

  /** Wires the repository. */
  public SignalsController(SignalRepository repository) {
    this.repository = repository;
  }

  /** Paged/filtered history. */
  @GetMapping
  public Map<String, Object> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID strategyVersionId,
      @RequestParam(required = false) String exchange,
      @RequestParam(required = false) String tradingsymbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int boundedLimit = Math.min(Math.max(limit, 1), 500);
    int boundedOffset = Math.max(offset, 0);
    List<SignalRepository.SignalRow> items =
        repository.list(
            status, strategyVersionId, exchange, tradingsymbol, from, to, boundedLimit,
            boundedOffset);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("items", items.stream().map(SignalsController::dto).toList());
    response.put("limit", boundedLimit);
    response.put("offset", boundedOffset);
    return response;
  }

  /** Currently live calls. */
  @GetMapping("/active")
  public Map<String, Object> active() {
    return Map.of("items", repository.active().stream().map(SignalsController::dto).toList());
  }

  /** Signal detail + the full reasoning payload. */
  @GetMapping("/{id}")
  public Map<String, Object> detail(@PathVariable long id) {
    SignalRepository.SignalRow row =
        repository.find(id)
            .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND_SIGNAL, "no such signal"));
    return dto(row);
  }

  /** Owner executed manually at the broker. */
  @PostMapping("/{id}/taken")
  public Map<String, Object> taken(
      @PathVariable long id, @RequestBody(required = false) TakenRequest request) {
    requireExists(id);
    repository.transition(id, "TAKEN");
    return detail(id);
  }

  /** Reject a call. */
  @PostMapping("/{id}/dismiss")
  public Map<String, Object> dismiss(@PathVariable long id) {
    requireExists(id);
    repository.transition(id, "DISMISSED");
    return detail(id);
  }

  private void requireExists(long id) {
    if (repository.find(id).isEmpty()) {
      throw new NotFoundException(ErrorCodes.NOT_FOUND_SIGNAL, "no such signal");
    }
  }

  private static Map<String, Object> dto(SignalRepository.SignalRow row) {
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("id", row.id());
    dto.put("strategyVersionId", row.strategyVersionId());
    dto.put("exchange", row.exchange());
    dto.put("tradingsymbol", row.tradingsymbol());
    dto.put("interval", row.interval());
    dto.put("signalType", row.signalType());
    dto.put("side", row.side());
    dto.put("entryPrice", row.entryPrice());
    dto.put("stopLoss", row.stopLoss());
    dto.put("target", row.target());
    dto.put("compositeScore", row.compositeScore());
    dto.put("scoreBreakdown", row.scoreBreakdown());
    dto.put("status", row.status());
    dto.put("generatedAt", row.generatedAt());
    dto.put("expiresAt", row.expiresAt());
    return dto;
  }
}
