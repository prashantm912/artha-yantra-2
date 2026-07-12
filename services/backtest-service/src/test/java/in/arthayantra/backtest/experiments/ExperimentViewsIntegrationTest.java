package in.arthayantra.backtest.experiments;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.jobs.EngineIdentity;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.provenance.DatasetProvenance;
import in.arthayantra.backtest.replay.MetricsCalculator.Metrics;
import in.arthayantra.backtest.replay.ReplayResult;
import in.arthayantra.backtest.replay.RunRepository;
import in.arthayantra.backtest.replay.options.PremiumSource;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Audit §11.4 / §13 #8+#11: the {@code experiment_runs} view (V010) + the {@link ExperimentController}
 * list/compare surface. Raw-JDBC over the singleton IT DB (the real backtest lineage incl. V010) — no
 * Spring context; the controller is exercised directly ({@code new
 * ExperimentController(repo)}). ITs share the DB with no per-method cleanup, so every assertion is
 * scoped to a per-test unique {@code strategyId} (list) or explicit seeded run ids (compare).
 */
class ExperimentViewsIntegrationTest extends BacktestIntegrationTestBase {

  private static JdbcTemplate jdbc;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @BeforeAll
  static void wire() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(jdbcUrl("backtest"), dbUser(), dbPassword());
    jdbc = new JdbcTemplate(ds);
  }

  /** One seeded experiment: a job (kind/parent/strategy context) + its completed run. Returns runId. */
  private static UUID seed(
      JobKind kind,
      UUID parentJobId,
      String strategyId,
      String strategyVersion,
      UUID strategyVersionId,
      String engineSha,
      String dataHash,
      String universeChecksum,
      PremiumSource premiumSource,
      String createdBy,
      BigDecimal totalReturn,
      String cagr) {
    return seed(
        kind, parentJobId, strategyId, strategyVersion, strategyVersionId, engineSha, dataHash,
        universeChecksum, premiumSource, createdBy, totalReturn, cagr,
        DatasetProvenance.none(), false);
  }

  /** Full-control seed: dataset provenance + the review-F2 premium flag (runner-stamped when true). */
  private static UUID seed(
      JobKind kind,
      UUID parentJobId,
      String strategyId,
      String strategyVersion,
      UUID strategyVersionId,
      String engineSha,
      String dataHash,
      String universeChecksum,
      PremiumSource premiumSource,
      String createdBy,
      BigDecimal totalReturn,
      String cagr,
      DatasetProvenance provenance,
      boolean premiumContentUnverified) {
    ObjectNode request = MAPPER.createObjectNode();
    request.put("strategyId", strategyId);
    request.put("strategyVersion", strategyVersion);
    request.put("purpose", "backtest");
    JobRepository jobs =
        new JobRepository(jdbc, MAPPER, EngineIdentity.of("job-sha", "backtest-service:it"));
    UUID jobId =
        jobs.insertQueued(kind, parentJobId, strategyVersionId, request, UUID.randomUUID().toString(), createdBy)
            .id();

    ObjectNode full = MAPPER.createObjectNode();
    full.put("cagr", cagr);
    if (premiumContentUnverified) {
      full.put("premiumContentUnverified", true); // the BacktestRunner stamp (only-when-true)
    }
    Metrics metrics =
        new Metrics(
            totalReturn,
            new BigDecimal("1.20"),
            new BigDecimal("1.50"),
            new BigDecimal("-5.00"),
            new BigDecimal("0.55"),
            new BigDecimal("1.80"),
            3,
            full);
    ReplayResult result =
        new ReplayResult(
            List.of(), List.of(), List.of(), List.of(),
            new BigDecimal("100000"), new BigDecimal("110000"), 0L, 0L);
    RunRepository runs = new RunRepository(jdbc, MAPPER, EngineIdentity.of(engineSha, engineSha + "-img"));
    return runs.insert(
        jobId,
        strategyVersionId,
        "NSE",
        "NIFTY 50",
        "1m",
        OffsetDateTime.parse("2026-01-05T09:15:00+05:30"),
        OffsetDateTime.parse("2026-01-05T15:30:00+05:30"),
        result,
        metrics,
        null,
        7L,
        dataHash,
        universeChecksum,
        "strategy-engine/test",
        premiumSource,
        null,
        null,
        provenance,
        createdBy);
  }

  /** A parent OPTIMIZATION job (a sweep produces no run itself) to hang TRIAL runs off. */
  private static UUID seedSweepParent(String strategyId) {
    ObjectNode request = MAPPER.createObjectNode();
    request.put("strategyId", strategyId);
    JobRepository jobs =
        new JobRepository(jdbc, MAPPER, EngineIdentity.of("job-sha", "backtest-service:it"));
    return jobs.insertQueued(
            JobKind.OPTIMIZATION, null, UUID.randomUUID(), request, UUID.randomUUID().toString(),
            "optimizer")
        .id();
  }

  private static ExperimentController controller() {
    return new ExperimentController(new ExperimentRepository(jdbc));
  }

  @Test
  void listScopesByStrategyAndCarriesJobContextAndProvenance() {
    String strategyId = "b8-list-" + UUID.randomUUID();
    UUID versionA = UUID.randomUUID();
    UUID versionB = UUID.randomUUID();
    UUID runA =
        seed(JobKind.BACKTEST, null, strategyId, "1.0.0", versionA, "sha-a", "hash-x", "uni-1",
            PremiumSource.NA, "owner", new BigDecimal("10.50"), "8.00");
    UUID runB =
        seed(JobKind.BACKTEST, null, strategyId, "1.0.0", versionA, "sha-a", "hash-x", "uni-1",
            PremiumSource.NA, "optimizer:sweep-1", new BigDecimal("20.00"), "15.00");
    UUID sweep = seedSweepParent(strategyId);
    UUID runC =
        seed(JobKind.TRIAL, sweep, strategyId, "2.0.0", versionB, "sha-b", "hash-y", null,
            PremiumSource.NA, "optimizer:sweep-1", new BigDecimal("-3.00"), "-1.00");

    List<ExperimentSummary> all = controller().experiments(strategyId, null, null, 50, 0).items();
    assertThat(all).extracting(ExperimentSummary::runId)
        .containsExactlyInAnyOrder(runA.toString(), runB.toString(), runC.toString());

    ExperimentSummary trial =
        all.stream().filter(s -> s.runId().equals(runC.toString())).findFirst().orElseThrow();
    assertThat(trial.kind()).isEqualTo("TRIAL");
    assertThat(trial.parentJobId()).isEqualTo(sweep.toString());
    assertThat(trial.engineSha()).isEqualTo("sha-b");
    assertThat(trial.createdBy()).isEqualTo("optimizer:sweep-1");
    assertThat(trial.strategyVersion()).isEqualTo("2.0.0");

    ExperimentSummary plain =
        all.stream().filter(s -> s.runId().equals(runA.toString())).findFirst().orElseThrow();
    assertThat(plain.kind()).isEqualTo("BACKTEST");
    assertThat(plain.parentJobId()).isNull();
    assertThat(plain.totalReturn()).isEqualTo("10.500000"); // NUMERIC(18,6) column scale
    assertThat(plain.tradeCount()).isEqualTo(3);

    // strategyVersionId filter narrows to the two v1.0.0 runs; kind filter narrows to the trial.
    assertThat(controller().experiments(strategyId, versionA, null, 50, 0).items())
        .extracting(ExperimentSummary::runId)
        .containsExactlyInAnyOrder(runA.toString(), runB.toString());
    assertThat(controller().experiments(strategyId, null, "TRIAL", 50, 0).items())
        .extracting(ExperimentSummary::runId)
        .containsExactly(runC.toString());
  }

  @Test
  void compareAlignsMetricsAndReadsLikeForLikeWhenIdentical() {
    String strategyId = "b8-cmp-eq-" + UUID.randomUUID();
    UUID version = UUID.randomUUID();
    UUID runA =
        seed(JobKind.BACKTEST, null, strategyId, "1.0.0", version, "sha-eq", "hash-eq", "uni-eq",
            PremiumSource.NA, "owner", new BigDecimal("12.34"), "9.00");
    UUID runB =
        seed(JobKind.BACKTEST, null, strategyId, "1.0.0", version, "sha-eq", "hash-eq", "uni-eq",
            PremiumSource.NA, "owner", new BigDecimal("56.78"), "40.00");

    ExperimentCompareResponse res = controller().compare(List.of(runA, runB));

    assertThat(res.runs()).extracting(ExperimentCompareRun::runId)
        .containsExactly(runA.toString(), runB.toString());
    ExperimentCompareRun a = res.runs().get(0);
    assertThat(a.metrics().totalReturn()).isEqualTo("12.340000");
    assertThat(a.metrics().cagr()).isEqualTo("9.00");
    assertThat(a.metrics().tradeCount()).isEqualTo(3);
    assertThat(a.engineSha()).isEqualTo("sha-eq");
    assertThat(a.createdBy()).isEqualTo("owner");
    assertThat(a.metrics().alpha()).isNull(); // no benchmark ⇒ NULL, never silently zero
    assertThat(res.runs().get(1).metrics().totalReturn()).isEqualTo("56.780000");

    LikeForLike flags = res.likeForLike();
    assertThat(flags.dataHashMatch()).isTrue();
    assertThat(flags.universeMatch()).isTrue();
    assertThat(flags.engineShaMatch()).isTrue();
    assertThat(flags.premiumSourceMatch()).isTrue();
  }

  @Test
  void compareFlagsNonLikeForLikePreservesOrderAndSkipsUnknownIds() {
    String strategyId = "b8-cmp-ne-" + UUID.randomUUID();
    UUID runX =
        seed(JobKind.BACKTEST, null, strategyId, "1.0.0", UUID.randomUUID(), "e1", "h1", "u1",
            PremiumSource.NA, "owner", new BigDecimal("5.00"), "4.00");
    UUID runY =
        seed(JobKind.BACKTEST, null, strategyId, "1.0.0", UUID.randomUUID(), "e2", "h2", null,
            PremiumSource.SNAPSHOT, "owner", new BigDecimal("7.00"), "6.00");
    UUID unknown = UUID.randomUUID();

    // Requested order [Y, unknown, X]: unknown is dropped, the rest keep request order.
    ExperimentCompareResponse res = controller().compare(List.of(runY, unknown, runX));

    assertThat(res.runs()).extracting(ExperimentCompareRun::runId)
        .containsExactly(runY.toString(), runX.toString());

    LikeForLike flags = res.likeForLike();
    assertThat(flags.dataHashMatch()).isFalse();
    assertThat(flags.universeMatch()).isFalse(); // "u1" vs NULL is a mismatch
    assertThat(flags.engineShaMatch()).isFalse();
    assertThat(flags.premiumSourceMatch()).isFalse(); // NA vs SNAPSHOT
  }

  @Test
  void compareIsEmptyWhenNoIdsGiven() {
    ExperimentCompareResponse res = controller().compare(null);
    assertThat(res.runs()).isEmpty();
    // A degenerate (empty) set is trivially like-for-like.
    assertThat(res.likeForLike().dataHashMatch()).isTrue();
  }

  // Review F3 + F2: the two roadmap-#30 axes treat NULL as UNKNOWN (any NULL ⇒ axis false — a set of
  // pre-V015 rows must never pass the refuse-to-rank gate), and the count-only option-premium caveat
  // surfaces per run + set-level on the like-for-like verdict.
  @Test
  void newAxesTreatNullAsUnknownAndSurfaceThePremiumCaveat() {
    String strategyId = "d4-f3-" + UUID.randomUUID();
    UUID version = UUID.randomUUID();
    DatasetProvenance stamped =
        new DatasetProvenance(
            "c-same", 3L, in.arthayantra.backtest.provenance.EvidencePolicy.SIM_FIRST);
    UUID runA =
        seed(JobKind.BACKTEST, null, strategyId, "1.0.0", version, "sha-f3", "h-f3", "u-f3",
            PremiumSource.NA, "owner", new BigDecimal("1.00"), "1.00", stamped, false);
    UUID runB =
        seed(JobKind.BACKTEST, null, strategyId, "1.0.0", version, "sha-f3", "h-f3", "u-f3",
            PremiumSource.CANDLE_1M, "owner", new BigDecimal("2.00"), "2.00", stamped, true);
    UUID runNull = // pre-V015 shape: no content hash / epoch / policy
        seed(JobKind.BACKTEST, null, strategyId, "1.0.0", version, "sha-f3", "h-f3", "u-f3",
            PremiumSource.NA, "owner", new BigDecimal("3.00"), "3.00");

    // Identically-stamped runs ARE like-for-like on the new axes; runB's count-only premium legs
    // surface per-run AND set-level.
    ExperimentCompareResponse both = controller().compare(List.of(runA, runB));
    assertThat(both.likeForLike().contentHashMatch()).isTrue();
    assertThat(both.likeForLike().datasetEpochMatch()).isTrue();
    assertThat(both.likeForLike().premiumContentUnverified()).isTrue();
    assertThat(both.runs().get(0).premiumContentUnverified()).isNull(); // no stamp ⇒ NULL in the view
    assertThat(both.runs().get(1).premiumContentUnverified()).isTrue();
    assertThat(both.runs().get(0).contentHash()).isEqualTo("c-same");
    assertThat(both.runs().get(0).datasetEpoch()).isEqualTo("3");

    // A verified-only set carries no premium caveat.
    ExperimentCompareResponse verifiedOnly = controller().compare(List.of(runA));
    assertThat(verifiedOnly.likeForLike().premiumContentUnverified()).isFalse();

    // One NULL run in the set fails BOTH new axes (the legacy axes still read NULL as a value).
    ExperimentCompareResponse mixed = controller().compare(List.of(runA, runNull));
    assertThat(mixed.likeForLike().contentHashMatch()).isFalse();
    assertThat(mixed.likeForLike().datasetEpochMatch()).isFalse();
    assertThat(mixed.likeForLike().dataHashMatch()).isTrue(); // legacy convention unchanged

    // Even two all-NULL runs fail: unknown content is never comparable.
    UUID runNull2 =
        seed(JobKind.BACKTEST, null, strategyId, "1.0.0", version, "sha-f3", "h-f3", "u-f3",
            PremiumSource.NA, "owner", new BigDecimal("4.00"), "4.00");
    ExperimentCompareResponse nulls = controller().compare(List.of(runNull, runNull2));
    assertThat(nulls.likeForLike().contentHashMatch()).isFalse();
    assertThat(nulls.likeForLike().datasetEpochMatch()).isFalse();
  }
}
