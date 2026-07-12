package in.arthayantra.backtest.provenance;

import java.util.List;

/**
 * The re-run policy verdict for one run (audit P1-1c / roadmap #30): is the run's dataset still the one
 * it executed against, or has a later data-rewrite touched its scope? SURFACING ONLY — it never triggers
 * an auto-rerun; a ranking client (Prompt 2 / the optimizer) reads {@code stale} and decides whether to
 * re-run the stale side before comparing. A run is {@code stale} iff an epoch row created AFTER its
 * stamped {@code datasetEpochAtRun} intersects its scope (its primary symbol/exchange + window, or a
 * global-scope epoch). {@code staleEpochs} lists exactly those rows so "what changed" is answered from
 * stored data. A TYPED record (never a {@code Map}).
 */
public record RunComparability(
    String runId,
    Long datasetEpochAtRun,
    long currentEpoch,
    boolean stale,
    String contentHash,
    String engineSha,
    String evidencePolicy,
    List<DatasetEpoch> staleEpochs) {}
