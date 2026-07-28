package in.arthayantra.gateway.status;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * The system-status rollup served by {@code GET /api/v1/system/status} — typed so the contract gate
 * can see it (ledger D3, Map-return burn-down).
 *
 * <p><b>This is a pure retyping: every key name, nesting level and value type is byte-identical to
 * the {@code LinkedHashMap} it replaces.</b> The wire does not change; what changes is that
 * springdoc can now enumerate the shape, so renaming or removing a key produces a spec diff and the
 * ci-contracts breaking gate can fail on it. A {@code Map<String, Object>} response is invisible to
 * that gate — adding, renaming or dropping keys inside one drifts nothing, which is why the two
 * comments inside the old assembler could truthfully say "Map return ⇒ this key never drifts the
 * contract". That property was a liability, not a feature.
 *
 * <p>Nullability is spelled {@code types = {"x", "null"}}, never {@code @Schema(nullable = true)} —
 * the latter is a SILENT NO-OP at OpenAPI 3.1 (swagger-core drops it), so it would publish these
 * fields as non-nullable and generate TS types that lie.
 */
public record SystemStatus(
    String overall,
    /* Back-compat alias for `overall`; both are emitted, exactly as before. */
    String status,
    List<ServiceStatus> services,
    KiteStatus kite,
    MarketStatus market,
    String corporateActions,
    /**
     * Deliberately left as a Map: the shape is owned by the jobs producer (Phase 28), not by this
     * rollup, so typing it here would freeze a contract this service does not define. Retyping the
     * ENVELOPE is what buys contract visibility; this one nested value stays honestly dynamic.
     */
    Map<String, Object> jobs,
    String asOf) {

  /** One service's health line in the rollup. */
  public record ServiceStatus(String name, String status) {}

  /** The Kite feed health block. */
  public record KiteStatus(
      String session,
      /* The RAW feed-state string ("MOCK"/"CONNECTED"/…). The rollup above maps MOCK → VALID for
       * the health view, which made the frontend's MOCK-mode tag unreachable (audit P1-11) — this
       * key is what restores it, and it is null when no producer has published yet. */
      @Schema(types = {"string", "null"}) String raw,
      String ticker,
      @Schema(types = {"integer", "null"}) Long lastTickAgeMs,
      /* Kite REST limiter headroom in [0,1]; null when absent or unparseable. */
      @Schema(types = {"number", "null"}) Double rateBudget) {}

  /** The market-phase block. */
  public record MarketStatus(String phase) {}
}
