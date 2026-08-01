package in.arthayantra.backtest.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.provenance.ProvenanceBlock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The wire contract of the D3 job-submission retypings, pinned at the SERIALIZED form (ledger D3
 * Map-return burn-down, backtest slice).
 *
 * <p>{@code JobLifecycleIntegrationTest} asserts these key sets over the real endpoint, which is
 * what proves the conversion changed nothing. This test pins the same shape without a container so
 * a future edit to {@link JobViews} reddens in milliseconds, and — the part the integration test
 * cannot reach — it pins the NULL-bearing provenance branch, where a submission block reports
 * "not known yet" for the fields the worker resolves later.
 *
 * <p>Scope: keys, order and null-vs-absent only. Both bodies came from {@code Map.of}, so the
 * ORDER assertions here are a NEW guarantee, not a preserved one: {@code Map.of} iteration order is
 * JVM-salted for the 3-key submission body (trivially stable for the 1-key cancel body).
 */
class JobViewsSerializationTest {

  private static final ObjectMapper OM = new ObjectMapper();

  private static List<String> keysOf(Object value) {
    List<String> keys = new ArrayList<>();
    OM.valueToTree(value).fieldNames().forEachRemaining(keys::add);
    return keys;
  }

  private static ProvenanceBlock submissionBlock() {
    // Exactly the shape JobsController.submissionProvenance builds: the six fields the worker
    // resolves at execution are null, profile + warmStatus are known at submit.
    return new ProvenanceBlock(
        "sha-abc", "image-abc", "checksum-abc", null, null, null, null, "universe-abc", null, null,
        "mock", "PREFLIGHT_OK", null);
  }

  @Test
  void theSubmissionBodyCarriesJobIdStatusThenProvenance() {
    JobViews.BacktestRunAccepted accepted =
        new JobViews.BacktestRunAccepted("job-1", "queued", submissionBlock());

    assertThat(keysOf(accepted)).containsExactly("jobId", "status", "provenance");
  }

  /**
   * The submission block's nulls must stay PRESENT-and-null, as the map made them. The service
   * configures no {@code NON_NULL} inclusion, and §D.15 treats a null here as the honest
   * "not resolved yet" signal — a client distinguishing that from "absent" depends on it.
   */
  @Test
  void theSubmissionProvenanceEmitsItsUnresolvedFieldsAsPresentAndNull() {
    JobViews.BacktestRunAccepted accepted =
        new JobViews.BacktestRunAccepted("job-1", "queued", submissionBlock());

    var provenance = OM.valueToTree(accepted).get("provenance");
    assertThat(keysOf(submissionBlock()))
        .containsExactly(
            "engineSha",
            "engineImage",
            "configHash",
            "dataHash",
            "contentHash",
            "datasetEpoch",
            "evidencePolicy",
            "universeChecksum",
            "premiumSource",
            "costClass",
            "profile",
            "warmStatus",
            "premiumContentUnverified");
    for (String unresolved :
        List.of(
            "dataHash",
            "contentHash",
            "datasetEpoch",
            "evidencePolicy",
            "premiumSource",
            "costClass",
            "premiumContentUnverified")) {
      assertThat(provenance.has(unresolved)).as(unresolved + " present").isTrue();
      assertThat(provenance.get(unresolved).isNull()).as(unresolved + " null").isTrue();
    }
  }

  @Test
  void theCancelBodyCarriesOnlyStatus() {
    assertThat(keysOf(new JobViews.JobCancelAccepted("cancelling"))).containsExactly("status");
  }
}
