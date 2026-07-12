package in.arthayantra.backtest.provenance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The dataset-comparability surface (audit P1-1 / R2, roadmap #22a/#30): records data-rewrite epochs,
 * builds the run provenance block, and computes the re-run policy staleness verdict. Pure orchestration
 * over {@link DatasetEpochRepository} + {@link RunProvenanceRepository}; no HTTP concerns.
 */
@Service
public class DatasetComparabilityService {

  private final DatasetEpochRepository epochs;
  private final RunProvenanceRepository runs;
  private final String profile;

  /** Wires the ledger + run-provenance repos and the runtime profile ({@code live} | {@code mock}). */
  public DatasetComparabilityService(
      DatasetEpochRepository epochs,
      RunProvenanceRepository runs,
      @Value("${spring.profiles.active:}") String activeProfiles) {
    this.epochs = epochs;
    this.runs = runs;
    // Mock and live are DB-isolated stacks; a mock run's synthetic-boot candles are never ranked, so
    // the profile is a first-class provenance field (§11.1). Default (no profile) reads as live.
    this.profile = activeProfiles != null && activeProfiles.contains("mock") ? "mock" : "live";
  }

  /** The current epoch head — the value a fresh run stamps. */
  public long head() {
    return epochs.head();
  }

  /** Records one data-rewrite epoch (parses the ISO window bounds; a bad reason/date surfaces upstream). */
  public DatasetEpoch record(DatasetEpochRequest req, String source) {
    return epochs.record(
        req.reason(),
        req.symbols(),
        req.exchange(),
        parse(req.windowStart()),
        parse(req.windowEnd()),
        req.interval(),
        req.jobLink(),
        source,
        req.note());
  }

  /** The recorded epochs, newest first + the head. */
  public DatasetEpochListResponse list(int limit, int offset) {
    return new DatasetEpochListResponse(epochs.list(limit, offset), epochs.head());
  }

  /** The full as-executed provenance block for a run, or empty when the run id is unknown. */
  public Optional<ProvenanceBlock> provenance(UUID runId) {
    return runs
        .findRow(runId)
        .map(
            r ->
                new ProvenanceBlock(
                    r.engineSha(),
                    r.engineImage(),
                    r.configHash(),
                    r.dataHash(),
                    r.contentHash(),
                    r.datasetEpoch(),
                    r.evidencePolicy(),
                    r.universeChecksum(),
                    r.premiumSource(),
                    r.costClass(),
                    profile,
                    null, // warmStatus is a submission-time field; not persisted per run
                    r.premiumContentUnverified()));
  }

  /**
   * The re-run policy verdict for a run (roadmap #30): stale iff a data-rewrite epoch recorded after the
   * run's stamped epoch intersects its scope. Empty when the run id is unknown. A run with a NULL stamped
   * epoch (pre-V015) is treated as epoch 0 — every recorded epoch is "after" it, so it is flagged stale
   * once any rewrite exists (honest: its dataset provenance is unknown).
   */
  public Optional<RunComparability> comparability(UUID runId) {
    return runs
        .findRow(runId)
        .map(
            r -> {
              long stampedEpoch = r.datasetEpoch() == null ? 0L : r.datasetEpoch();
              long currentEpoch = epochs.head();
              List<DatasetEpoch> stale =
                  epochs.affecting(
                      stampedEpoch, r.exchange(), r.tradingsymbol(), r.startTs(), r.endTs());
              return new RunComparability(
                  runId.toString(),
                  r.datasetEpoch(),
                  currentEpoch,
                  !stale.isEmpty(),
                  r.contentHash(),
                  r.engineSha(),
                  r.evidencePolicy(),
                  stale);
            });
  }

  private static OffsetDateTime parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.length() == 10 ? value + "T00:00:00+05:30" : value;
    return OffsetDateTime.parse(normalized);
  }
}
