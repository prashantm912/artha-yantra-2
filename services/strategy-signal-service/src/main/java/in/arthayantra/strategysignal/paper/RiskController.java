package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
      Set.of(
          RiskService.KILL_SWITCH,
          RiskService.MAX_OPEN,
          RiskService.DAILY_LOSS,
          RiskService.DAILY_PROFIT_TARGET,
          RiskService.MAX_DEPLOYMENT_PCT,
          RiskService.AUTO_PAPER_TRADE);

  private static final String DEFAULT_BOOK = "scalper";

  /** A typed limit update: the book, the key and its JSONB payload (incl. {@code enabled}). */
  public record UpdateBody(String book, String key, JsonNode value) {}

  private final RiskService risk;
  private final ObjectMapper objectMapper;

  /** Wires the risk service. */
  public RiskController(RiskService risk, ObjectMapper objectMapper) {
    this.risk = risk;
    this.objectMapper = objectMapper;
  }

  /** A book's current limits + the recent trip/flip audit ({@code book} absent → scalper). */
  @GetMapping("/settings")
  public RiskViews.RiskSettings settings(@RequestParam(required = false) String book) {
    String b = (book == null || book.isBlank()) ? DEFAULT_BOOK : book;
    return risk.settingsView(b);
  }

  /** Upsert one of a book's limits (the E-14 pattern: DB row, never YAML). */
  @PutMapping("/settings")
  public RiskViews.RiskSettings update(@RequestBody UpdateBody body) {
    if (body.key() == null || !KEYS.contains(body.key()) || body.value() == null) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "key must be one of " + KEYS);
    }
    // Require an explicit book on a write so an owner never silently edits the wrong book's limit.
    if (body.book() == null || body.book().isBlank()) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "book is required to edit a risk limit");
    }
    String book = body.book();
    try {
      risk.update(book, body.key(), objectMapper.writeValueAsString(body.value()));
    } catch (JsonProcessingException e) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "value is not serializable JSON");
    }
    return settings(book);
  }
}
