package in.arthayantra.backtest.provenance;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The run/submission provenance block (audit §11.1, roadmap #22a) — one TYPED record answering "what
 * exactly produced these numbers", surfaced on a job submission response (the as-submitted, partial
 * shape) and on a run read (the as-executed, full shape). NULL fields are the honest "not known
 * yet / not applicable" signal: at submission the content hash + epoch + evidence policy are not yet
 * resolved (the run has read no candles); a mock/test jar leaves the engine identity NULL.
 *
 * <p>Fields (superset; each context fills what it knows):
 *
 * <ul>
 *   <li>{@code engineSha}/{@code engineImage} — the engine CODE identity (V008 / audit R1).
 *   <li>{@code configHash} — the strategy version's immutable SHA-256 checksum (the config leg).
 *   <li>{@code dataHash} — the freshness-proxy reproducibility leg (V003).
 *   <li>{@code contentHash} — the content-stable fingerprint (P1-1 / R2).
 *   <li>{@code datasetEpoch} — the epoch head stamped at execution (§11.3).
 *   <li>{@code evidencePolicy} — SIM_FIRST / LIVE_FIRST / SIM_BLOCKED (§1.2, #22b).
 *   <li>{@code universeChecksum} — the pinned-universe hash (NULL for single-instrument runs).
 *   <li>{@code premiumSource} — the execution basis (SNAPSHOT / SYNTHETIC_B76 / CANDLE_1M / NA).
 *   <li>{@code costClass} — the statutory cost stack the net numbers were priced under (P1-2).
 *   <li>{@code profile} — {@code live} | {@code mock} (mock runs are never ranked).
 *   <li>{@code warmStatus} — the submission-time auto-warm/preflight outcome ({@code null} on run reads).
 *   <li>{@code premiumContentUnverified} (review F2) — {@code true} iff the run consumed traded-option
 *       premium legs: those series contribute bar-count only to {@code contentHash}, so equality of the
 *       content-hash/epoch axes does not guarantee premium-data equality for such a run. {@code null}
 *       at submission (unknown until the replay runs).
 * </ul>
 */
public record ProvenanceBlock(
    @Schema(types = {"string", "null"}) String engineSha,
    @Schema(types = {"string", "null"}) String engineImage,
    @Schema(types = {"string", "null"}) String configHash,
    @Schema(types = {"string", "null"}) String dataHash,
    @Schema(types = {"string", "null"}) String contentHash,
    @Schema(types = {"integer", "null"}) Long datasetEpoch,
    @Schema(types = {"string", "null"}) String evidencePolicy,
    @Schema(types = {"string", "null"}) String universeChecksum,
    @Schema(types = {"string", "null"}) String premiumSource,
    @Schema(types = {"string", "null"}) String costClass,
    String profile,
    @Schema(types = {"string", "null"}) String warmStatus,
    @Schema(types = {"boolean", "null"}) Boolean premiumContentUnverified) {}
