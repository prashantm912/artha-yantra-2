package in.arthayantra.backtest.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the authoritative {@code jobs} table (§D.3). {@code request} carries the full job
 * spec (symbols, interval, range, method, overrides, resolved universe copy, {@code purpose}) as
 * JSONB. {@code parentJobId} links a sweep's trials to the parent OPTIMIZATION row.
 * {@code createdBy} is the actor that submitted the job (audit T3 / EVO §13 row 4): {@code 'owner'}
 * for the API path, {@code 'optimizer'}/{@code 'optimizer:{sweepId}'} for optimizer sweeps/trials,
 * {@code null} on pre-V009 rows. The worker copies it forward onto the run row.
 */
public record Job(
    UUID id,
    JobKind kind,
    UUID parentJobId,
    JobStatus status,
    int progress,
    UUID strategyVersionId,
    JsonNode request,
    String error,
    String workerId,
    String correlationId,
    OffsetDateTime createdAt,
    OffsetDateTime startedAt,
    OffsetDateTime finishedAt,
    String createdBy) {}
