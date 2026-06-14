package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Global risk-limit settings (A12 / FP-42), routed by edge-gateway under {@code /api/v1/risk/**}.
 * Limits live on DB rows here, never in strategy YAML — a flip never mints a version or perturbs a
 * checksum. The kill switch is the one-click pause-all.
 */
@RestController
@RequestMapping("/api/v1/risk")
public class RiskController {

  private static final Set<String> KEYS =
      Set.of(RiskService.KILL_SWITCH, RiskService.MAX_OPEN, RiskService.DAILY_LOSS);

  /** A typed limit update: the key and its JSONB payload (incl. {@code enabled}). */
  public record UpdateBody(String key, JsonNode value) {}

  private final RiskService risk;
  private final ObjectMapper objectMapper;

  /** Wires the risk service. */
  public RiskController(RiskService risk, ObjectMapper objectMapper) {
    this.risk = risk;
    this.objectMapper = objectMapper;
  }

  /** Current limits + the recent trip/flip audit. */
  @GetMapping("/settings")
  public Map<String, Object> settings() {
    List<Map<String, Object>> items =
        risk.all().stream()
            .map(
                s -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("key", s.key());
                  row.put("value", s.value());
                  row.put("updatedAt", s.updatedAt());
                  return row;
                })
            .toList();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("items", items);
    response.put("audit", risk.audit(20));
    return response;
  }

  /** Upsert one limit (the E-14 pattern: DB row, never YAML). */
  @PutMapping("/settings")
  public Map<String, Object> update(@RequestBody UpdateBody body) {
    if (body.key() == null || !KEYS.contains(body.key()) || body.value() == null) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "key must be one of " + KEYS);
    }
    try {
      risk.update(body.key(), objectMapper.writeValueAsString(body.value()));
    } catch (JsonProcessingException e) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "value is not serializable JSON");
    }
    return settings();
  }
}
