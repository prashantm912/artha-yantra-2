package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The {@code GET /api/v1/backtests/{id}/results} body: metrics + curves + the reproducibility triple
 * for one run.
 *
 * <p>A PURE RETYPING of the {@code LinkedHashMap} {@code RunRepository.findResult} used to return
 * (ledger D3 slice 1). Every key name, order, nesting level and value type is unchanged — only the
 * SPEC gains the shape, because springdoc cannot enumerate a {@code Map<String, Object>} and so the
 * contract gate was blind to any key rename here.
 *
 * <p><b>Component order is load-bearing.</b> The map was a {@code LinkedHashMap} and Jackson emits a
 * record in canonical-constructor order, so these components MUST stay in the repository's original
 * put order or the serialized bytes move. Nulls are emitted, not omitted (the service configures no
 * {@code NON_NULL} inclusion), which matches the map's behaviour for an absent column.
 *
 * <p>Nullability is spelled {@code types = {"x", "null"}} rather than {@code nullable = true} —
 * the latter is a SILENT NO-OP at OpenAPI 3.1 (swagger-core's 3.1 serializer drops it).
 */
public record RunResult(
    JsonNode metrics,
    JsonNode equityCurve,
    JsonNode drawdownCurve,
    JsonNode benchmarkCurve,
    @Schema(types = {"string", "null"}) String dataHash,
    long seed,
    @Schema(types = {"string", "null"}) String premiumSource,
    /** The originating strategy UUID; the results header maps it to a name client-side. */
    @Schema(types = {"string", "null"}) String strategyId,
    /** {@code completed_at} — "the date when it was run". */
    @Schema(types = {"string", "null"}) String ranAt,
    @Schema(types = {"string", "null"}) String exchange,
    @Schema(types = {"string", "null"}) String tradingsymbol,
    /** NULL for explicit single-instrument / unpinned universes — the compare banner ignores NULLs. */
    @Schema(types = {"string", "null"}) String universeChecksum,
    /** The resolved universe's asOf (funnel-chosen screen date / constituents asOf), else NULL. */
    @Schema(types = {"string", "null"}) String universeAsOf,
    /** Engine git SHA; NULL on pre-V008 rows and on a jar built without git.properties. */
    @Schema(types = {"string", "null"}) String engineSha,
    @Schema(types = {"string", "null"}) String engineImage,
    /** §D.15 synthetic-premium caveats, surfaced at the top level rather than buried in metrics. */
    List<String> caveats) {}
