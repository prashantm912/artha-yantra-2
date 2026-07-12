package in.arthayantra.backtest.provenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.jobs.EngineIdentity;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.replay.MetricsCalculator.Metrics;
import in.arthayantra.backtest.replay.ReplayResult;
import in.arthayantra.backtest.replay.RunRepository;
import in.arthayantra.backtest.replay.options.PremiumSource;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import in.arthayantra.common.web.error.ApiException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Audit P1-1 / R2 (roadmap #22a/#30): the {@code dataset_epochs} ledger + the re-run policy staleness
 * verdict + the run provenance block, over the singleton IT DB (the real backtest lineage incl. V015).
 * Raw-JDBC, no Spring context. ITs share the DB with no cleanup, so every scenario uses a UNIQUE symbol
 * and asserts on the SPECIFIC epoch ids it records (never absolute head values); the tests NEVER record
 * a global-scope epoch, so a run's staleness is driven solely by its own symbol's epochs.
 */
class DatasetEpochIntegrationTest extends BacktestIntegrationTestBase {

  private static JdbcTemplate jdbc;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static DatasetEpochRepository epochs;
  private static DatasetComparabilityService service;

  private static final OffsetDateTime RUN_FROM =
      OffsetDateTime.parse("2026-02-02T09:15:00+05:30");
  private static final OffsetDateTime RUN_TO = OffsetDateTime.parse("2026-02-02T15:30:00+05:30");

  @BeforeAll
  static void wire() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(jdbcUrl("backtest"), dbUser(), dbPassword());
    jdbc = new JdbcTemplate(ds);
    epochs = new DatasetEpochRepository(jdbc);
    service =
        new DatasetComparabilityService(epochs, new RunProvenanceRepository(jdbc, MAPPER), "");
  }

  private static UUID newJobId() {
    JobRepository jobs =
        new JobRepository(jdbc, MAPPER, EngineIdentity.of("job-sha", "backtest-service:it"));
    return jobs.insertQueued(
            JobKind.BACKTEST,
            null,
            UUID.randomUUID(),
            MAPPER.createObjectNode().put("strategyId", "epoch-it"),
            UUID.randomUUID().toString(),
            "owner")
        .id();
  }

  /** Seeds a completed run at {@code (NSE, symbol)} over the run window, stamped with the provenance. */
  private static UUID seedRun(String symbol, DatasetProvenance provenance) {
    RunRepository runs =
        new RunRepository(jdbc, MAPPER, EngineIdentity.of("engine-sha-it", "backtest-service:it"));
    ReplayResult result =
        new ReplayResult(
            List.of(), List.of(), List.of(), List.of(),
            new BigDecimal("100000"), new BigDecimal("100000"), 0L, 0L);
    Metrics metrics =
        new Metrics(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, 0, MAPPER.createObjectNode());
    return runs.insert(
        newJobId(), UUID.randomUUID(), "NSE", symbol, "1m", RUN_FROM, RUN_TO, result, metrics,
        null, 7L, "data-hash", null, "strategy-engine/test", PremiumSource.NA, null, null,
        provenance, "owner");
  }

  @Test
  void recordBumpsHeadAndRoundTripsScope() {
    long before = service.head();
    DatasetEpoch e =
        service.record(
            new DatasetEpochRequest(
                "CA_READJUST", List.of("RELIANCE", "TCS"), "NSE",
                "2026-01-01", "2026-01-02", "1d", "job-42", "split adjust"),
            "owner");
    assertThat(e.id()).isGreaterThan(before);
    assertThat(service.head()).isEqualTo(e.id());
    assertThat(e.reason()).isEqualTo("CA_READJUST");
    assertThat(e.symbols()).containsExactly("RELIANCE", "TCS");
    assertThat(e.jobLink()).isEqualTo("job-42");
    assertThat(e.source()).isEqualTo("owner");
  }

  @Test
  void runIsStaleWhenALaterEpochTouchesItsScope() {
    String sym = "D4-STALE-" + UUID.randomUUID();
    long stamp = service.head();
    UUID runId = seedRun(sym, new DatasetProvenance("content-1", stamp, EvidencePolicy.SIM_FIRST));

    // Nothing recorded after the stamp for THIS symbol yet → not stale.
    RunComparability before = service.comparability(runId).orElseThrow();
    assertThat(before.stale()).isFalse();
    assertThat(before.datasetEpochAtRun()).isEqualTo(stamp);

    // A later re-fetch of this symbol over an OVERLAPPING window makes the run stale.
    DatasetEpoch touch =
        service.record(
            new DatasetEpochRequest(
                "AUTHORITATIVE_REFETCH", List.of(sym), "NSE",
                "2026-02-02T10:00:00+05:30", "2026-02-02T11:00:00+05:30", "1m", null, "re-fetch"),
            "owner");

    RunComparability after = service.comparability(runId).orElseThrow();
    assertThat(after.stale()).isTrue();
    assertThat(after.currentEpoch()).isEqualTo(touch.id());
    assertThat(after.staleEpochs()).extracting(DatasetEpoch::id).contains(touch.id());
  }

  @Test
  void runIsNotStaleForADifferentSymbolOrNonOverlappingWindow() {
    String sym = "D4-SCOPE-" + UUID.randomUUID();
    long stamp = service.head();
    UUID runId = seedRun(sym, new DatasetProvenance("content-2", stamp, EvidencePolicy.SIM_FIRST));

    // A rewrite of a DIFFERENT symbol does not touch this run.
    DatasetEpoch otherSymbol =
        service.record(
            new DatasetEpochRequest(
                "BACKFILL", List.of("D4-OTHER-" + UUID.randomUUID()), "NSE",
                null, null, null, null, null),
            "owner");
    // A rewrite of THIS symbol over a window BEFORE the run window does not touch it either.
    DatasetEpoch pastWindow =
        service.record(
            new DatasetEpochRequest(
                "BACKFILL", List.of(sym), "NSE",
                "2025-01-01", "2025-01-02", "1d", null, null),
            "owner");

    RunComparability c = service.comparability(runId).orElseThrow();
    assertThat(c.stale()).isFalse();
    assertThat(c.staleEpochs())
        .extracting(DatasetEpoch::id)
        .doesNotContain(otherSymbol.id(), pastWindow.id());
  }

  @Test
  void globalScopeEpochTouchesAnyRunWindow() {
    String sym = "D4-GLOBAL-" + UUID.randomUUID();
    long stamp = service.head();
    UUID runId = seedRun(sym, new DatasetProvenance("content-3", stamp, EvidencePolicy.SIM_FIRST));

    // A whole-store rewrite (symbols NULL, window NULL) touches every prior run regardless of symbol.
    DatasetEpoch global =
        service.record(
            new DatasetEpochRequest("MANUAL", null, null, null, null, null, null, "full rebuild"),
            "owner");

    RunComparability c = service.comparability(runId).orElseThrow();
    assertThat(c.stale()).isTrue();
    assertThat(c.staleEpochs()).extracting(DatasetEpoch::id).contains(global.id());
  }

  @Test
  void provenanceReflectsTheRunStamp() {
    String sym = "D4-PROV-" + UUID.randomUUID();
    long stamp = service.head();
    UUID runId = seedRun(sym, new DatasetProvenance("content-prov", stamp, EvidencePolicy.LIVE_FIRST));

    ProvenanceBlock block = service.provenance(runId).orElseThrow();
    assertThat(block.contentHash()).isEqualTo("content-prov");
    assertThat(block.datasetEpoch()).isEqualTo(stamp);
    assertThat(block.evidencePolicy()).isEqualTo("LIVE_FIRST");
    assertThat(block.engineSha()).isEqualTo("engine-sha-it");
    assertThat(block.dataHash()).isEqualTo("data-hash");
    assertThat(block.profile()).isEqualTo("live");
    // review F2: no runner stamp on this seeded (empty-metrics) run ⇒ verified/candle reads false
    assertThat(block.premiumContentUnverified()).isFalse();
  }

  @Test
  void unknownRunHasNoProvenanceOrComparability() {
    UUID missing = UUID.randomUUID();
    assertThat(service.provenance(missing)).isEmpty();
    assertThat(service.comparability(missing)).isEmpty();
  }

  @Test
  void controllerRejectsAnUnknownReason() {
    DatasetComparabilityController controller = new DatasetComparabilityController(service);
    assertThatThrownBy(
            () ->
                controller.recordEpoch(
                    new DatasetEpochRequest("NONSENSE", null, null, null, null, null, null, null)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("reason must be one of");
    // a lower-case known reason is normalized + accepted
    DatasetEpoch ok =
        controller.recordEpoch(
            new DatasetEpochRequest(
                "bhavcopy_fill", List.of("INFY"), "NSE", null, null, "1d", null, null));
    assertThat(ok.reason()).isEqualTo("BHAVCOPY_FILL");
  }

  // Review F4: a malformed window bound is a caller error — 422 VALIDATION_FAILED, never a 500
  // (DateTimeParseException must not escape to the catch-all).
  @Test
  void controllerRejectsAMalformedWindowWith422() {
    DatasetComparabilityController controller = new DatasetComparabilityController(service);
    assertThatThrownBy(
            () ->
                controller.recordEpoch(
                    new DatasetEpochRequest(
                        "MANUAL", null, null, "not-a-date", null, null, null, null)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("windowStart/windowEnd")
        .extracting(e -> ((ApiException) e).httpStatus())
        .isEqualTo(422);
    // and a malformed END bound too (both bounds route through the same parse)
    assertThatThrownBy(
            () ->
                controller.recordEpoch(
                    new DatasetEpochRequest(
                        "MANUAL", null, null, null, "2026-13-45T99:00:00", null, null, null)))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).httpStatus())
        .isEqualTo(422);
  }
}
