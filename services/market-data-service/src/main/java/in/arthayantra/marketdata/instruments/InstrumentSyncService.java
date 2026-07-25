package in.arthayantra.marketdata.instruments;

import in.arthayantra.common.web.error.ConflictException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import in.arthayantra.marketdata.kite.InstrumentDumpGateway;
import in.arthayantra.marketdata.kite.InstrumentDumpGateway.InstrumentRecord;
import java.time.Duration;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

/**
 * The B-3 dump-sync order of operations, steps 3–7: stage (JDBC batch) → spec-history diff
 * (Phase 9A hook) → atomic upsert → tombstone vanished → rebuild the token↔symbol map → evict
 * the instrument/expiry caches. Runs async on the service executor; progress readable from
 * {@link #status()} — deliberately NO jobs-table dependency (that ships in Phase 28).
 */
@Service
public class InstrumentSyncService {

  private static final Logger log = LoggerFactory.getLogger(InstrumentSyncService.class);

  /** Audit of the last sync run (B-1 /instruments/sync/status). */
  public record SyncStatus(
      @Schema(types = {"string", "null"}) String jobId,
      String state, // RUNNING / OK / FAILED / NEVER_RUN
      @Schema(types = {"string", "null"}) Instant lastRun,
      Map<String, Long> perExchangeRows,
      long durationMs,
      @Schema(types = {"string", "null"}) String error) {}

  private final InstrumentDumpGateway dumpGateway;
  private final InstrumentRepository repository;
  private final InstrumentRegistry registry;
  private final CacheManager cacheManager;
  private final ObjectProvider<SyncStep> syncSteps;
  private final org.springframework.context.ApplicationEventPublisher eventPublisher;
  private final IngestRunLedger ledger;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "instrument-sync");
            t.setDaemon(true);
            return t;
          });
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicReference<SyncStatus> status =
      new AtomicReference<>(new SyncStatus(null, "NEVER_RUN", null, Map.of(), 0, null));

  /** Wires the pipeline collaborators; {@link SyncStep} beans (Phase 9A) hook between 3 and 4. */
  public InstrumentSyncService(
      InstrumentDumpGateway dumpGateway,
      InstrumentRepository repository,
      InstrumentRegistry registry,
      CacheManager cacheManager,
      ObjectProvider<SyncStep> syncSteps,
      org.springframework.context.ApplicationEventPublisher eventPublisher,
      IngestRunLedger ledger) {
    this.dumpGateway = dumpGateway;
    this.repository = repository;
    this.registry = registry;
    this.cacheManager = cacheManager;
    this.syncSteps = syncSteps;
    this.eventPublisher = eventPublisher;
    this.ledger = ledger;
  }

  /** 202-style async trigger; 409 {@code CONFLICT_SYNC_RUNNING} when one is in flight. */
  @SuppressWarnings("FutureReturnValueIgnored") // outcome lands in status(), not the Future
  public String triggerAsync() {
    if (!running.compareAndSet(false, true)) {
      throw new ConflictException(ErrorCodes.CONFLICT_SYNC_RUNNING, "instrument sync already running");
    }
    String jobId = UUID.randomUUID().toString();
    status.set(new SyncStatus(jobId, "RUNNING", Instant.now(), Map.of(), 0, null));
    executor.submit(() -> runLocked(jobId));
    return jobId;
  }

  /** Synchronous run (startup bootstrap + scheduler). No-op result if one is already running. */
  public SyncStatus runSync() {
    if (!running.compareAndSet(false, true)) {
      return status.get();
    }
    return runLocked(UUID.randomUUID().toString());
  }

  private SyncStatus runLocked(String jobId) {
    Instant started = Instant.now();
    // Ingest-run ledger (audit §7.2.3): a full dump has no data window; rows_written = instruments synced.
    Long runId = ledger.start(IngestRunLedger.SOURCE_INSTRUMENT_SYNC);
    try {
      List<InstrumentRecord> dump = dumpGateway.fetchDump();
      repository.stageDump(dump);
      // Phase 9A and friends: set-based steps between staging and upsert
      syncSteps.orderedStream().forEach(SyncStep::afterStaging);
      repository.upsertFromStaging();
      final int tombstoned = repository.tombstoneVanished();
      registry.rebuildFromDatabase();
      evict("instruments");
      evict("expiries");
      // kite-side consumers (pinned indices, 15A futures pins) re-resolve on this
      eventPublisher.publishEvent(
          new in.arthayantra.marketdata.kite.InstrumentMasterUpdated(repository.countActive()));
      Map<String, Long> perExchange =
          dump.stream()
              .collect(Collectors.groupingBy(InstrumentRecord::exchange, Collectors.counting()));
      SyncStatus ok =
          new SyncStatus(
              jobId,
              "OK",
              started,
              perExchange,
              Duration.between(started, Instant.now()).toMillis(),
              null);
      status.set(ok);
      ledger.succeed(runId, dump.size());
      log.info(
          "instrument sync ok: {} rows, {} tombstoned, {} ms",
          dump.size(),
          tombstoned,
          ok.durationMs());
      return ok;
    } catch (Exception e) {
      SyncStatus failed =
          new SyncStatus(
              jobId,
              "FAILED",
              started,
              Map.of(),
              Duration.between(started, Instant.now()).toMillis(),
              e.getMessage());
      status.set(failed);
      ledger.fail(runId, e.getMessage());
      log.error("instrument sync failed", e);
      return failed;
    } finally {
      running.set(false);
    }
  }

  /**
   * Last sync audit. The in-memory audit resets to NEVER_RUN on every restart, which asserted a
   * falsehood on a long-synced system (audit 2026-07-02 §3 — Settings read "Data sync: NEVER_RUN"
   * live). When the DB proves a prior sync (instruments carry {@code last_seen_at}), report OK with
   * that timestamp instead; per-run details (rows/duration) are gone with the restart.
   */
  public SyncStatus status() {
    SyncStatus s = status.get();
    if ("NEVER_RUN".equals(s.state())) {
      Instant lastSeen = repository.maxLastSeenAt();
      if (lastSeen != null) {
        return new SyncStatus(null, "OK", lastSeen, Map.of(), 0, null);
      }
    }
    return s;
  }

  private void evict(String cache) {
    var c = cacheManager.getCache(cache);
    if (c != null) {
      c.clear();
    }
  }

  /** A set-based step running between dump staging and the master upsert (B-20). */
  public interface SyncStep {
    /** Invoked after staging is loaded, before the master upsert. */
    void afterStaging();
  }
}
