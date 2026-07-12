package in.arthayantra.backtest.provenance;

/**
 * The three dataset-comparability values the worker stamps onto a run row alongside the existing
 * reproducibility triple (audit P1-1 / R2, roadmap #22a/#22b): the content-stable {@code contentHash},
 * the {@code datasetEpoch} head observed at execution, and the family {@code evidencePolicy}. Bundled as
 * one value so {@code RunRepository.insert} gains a single self-documenting parameter rather than three
 * positional ones. {@link #none()} is the empty stamp for a writer that computes none of them (a
 * mock/test path) — all three columns then persist NULL.
 */
public record DatasetProvenance(String contentHash, Long datasetEpoch, EvidencePolicy evidencePolicy) {

  /** The empty stamp — all three columns persist NULL (pre-V015 / mock / test rows). */
  public static DatasetProvenance none() {
    return new DatasetProvenance(null, null, null);
  }

  /** The persisted evidence-policy name, or {@code null} when unset. */
  public String evidencePolicyName() {
    return evidencePolicy == null ? null : evidencePolicy.name();
  }
}
