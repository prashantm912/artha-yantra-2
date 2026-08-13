package in.arthayantra.marketdata.options;

import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled retention for {@code marketdata.options_chain_snapshots} (ledger A10, owner decision
 * 2026-07-12). Every day at 18:05 IST (post-close) it drops whole 1-day chunks older than the
 * {@code artha.market.snapshot-retention-days} horizon (default 365) via TimescaleDB
 * {@code drop_chunks} — chunk-wise and compression-safe. It NEVER row-wise {@code DELETE}s: the
 * table is a ~1.12B-row-class compressed hypertable and a naive delete re-OOMs the DB (the CA-purge
 * incident #680 proved the ~100k decompression limit). It calls {@code public.drop_chunks} DIRECTLY,
 * not V010's {@code marketdata.prune_options_snapshots} wrapper — that LANGUAGE-sql function returns
 * {@code SETOF regclass} and errors "relation ... does not exist" when invoked programmatically
 * (it re-resolves each just-dropped chunk's regclass on return; reproduced on Timescale 2.17.2).
 *
 * <p><b>Owner override of amendment A2.</b> V006/V010 documented an A2 ">= 5 year floor, default
 * no-drop" for this table; the owner's 2026-07-12 decision replaces that with a 365-day scheduled
 * horizon. The horizon is env-tunable ({@code ARTHA_SNAPSHOT_RETENTION_DAYS}) precisely so a
 * checksum-locked migration is not needed to retune it — which is also why this is a Java job rather
 * than a Timescale {@code add_retention_policy} (a native policy cannot read a Spring/env knob, would
 * write no {@code ingest_runs} audit row, and would be invisible to {@code docker logs}).
 *
 * <p><b>No-op until 2027-06.</b> Full-chain OI capture began 2026-06-15, so nothing is older than
 * 365 days until ~2027-06-15. Until then every run previews an empty chunk set and records a
 * {@code rows_written=0} {@code SUCCESS} row — a safe, logged no-op. {@code drop_chunks} is
 * conservative: it only drops a chunk whose entire time range is older than the cutoff, so it never
 * removes a chunk that still holds any row inside the horizon.
 *
 * <p><b>Degradation after a drop.</b> Once a window is pruned, OI history for that window falls back
 * to candle-derived OI (bucketed {@code last(oi)} pivot, IV/greeks null) via {@code HistoricalOiReader}
 * — the documented data-fidelity degradation, not a data loss (the per-contract {@code candles}
 * survive; only the snapshot-grade IV/greeks are gone).
 *
 * <p>Fail-soft like the other batch jobs here: the pass is wrapped, a failure is logged, recorded as
 * a {@code FAILURE} {@code ingest_runs} row and ntfy-alerted, and NEVER propagates out of the
 * {@code @Scheduled} tick. A missed cron while the stack is down does not fire later (batch-liveness
 * doctrine) — retention is not time-critical, the next day's tick catches up.
 */
@Component
public class OptionsSnapshotPruneJob {

  /**
   * The {@code ingest_runs} source label for this job. Deliberately NOT one of {@link
   * IngestRunLedger}'s enumerated capture/screen sources and NOT in {@code IngestCoverageCanary}'s
   * EXPECTED matrix — a prune is a maintenance run, not an expected daily data ingest, so it records
   * for the ingest-health board without ever RED-flagging the T+1 coverage canary.
   */
  static final String SOURCE = "OPTIONS_SNAPSHOT_PRUNE";

  private static final String TABLE = "marketdata.options_chain_snapshots";

  private static final Logger log = LoggerFactory.getLogger(OptionsSnapshotPruneJob.class);

  private final JdbcTemplate jdbc;
  private final IngestRunLedger ledger;
  private final NtfyClient ntfy;
  private final boolean live;
  private final int retentionDays;

  /** Wires the DB access, the audit ledger, alerting and the (env-tunable) retention horizon. */
  public OptionsSnapshotPruneJob(
      JdbcTemplate jdbc,
      IngestRunLedger ledger,
      NtfyClient ntfy,
      Environment environment,
      @Value("${artha.market.snapshot-retention-days:365}") int retentionDays) {
    this.jdbc = jdbc;
    this.ledger = ledger;
    this.ntfy = ntfy;
    this.live = environment.matchesProfiles("live");
    this.retentionDays = retentionDays;
  }

  /**
   * The daily retention tick (18:05 IST — The 02:30/03:30 slot was retired on 2026-08-12: the machine is shut down at 19:00 IST and started after 08:00, so an overnight tick never fired at all — the retention this job exists to enforce was simply not happening. Six-field Spring cron with the explicit
   * Asia/Kolkata zone
   * (B-9 convention, machine-checked by {@code CronConventionsTest}); runs every calendar day since
   * retention has no trading-day dependence. Live-only: the mock/CI database is ephemeral
   * ({@code artha_mock}, wiped by {@code ay reset-db}), so there is nothing to retain there.
   */
  @Scheduled(cron = "0 5 18 * * *", zone = "Asia/Kolkata")
  public void scheduledPrune() {
    if (!live) {
      return;
    }
    prune();
  }

  /**
   * One retention pass: preview the chunks past the horizon, drop them chunk-wise, and record the
   * outcome to {@code ingest_runs}. Package-visible so the IT drives it directly (bypassing the
   * live-profile gate on the scheduled entry point). Fully fail-soft — returns rather than throws.
   *
   * @return the regclass names of the chunks dropped this pass (empty on a no-op day or a failure)
   */
  List<String> prune() {
    Long runId = ledger.start(SOURCE);
    try {
      if (retentionDays <= 0) {
        // Guard: older_than => interval '0 days' means now(), which would drop EVERY chunk. A
        // misconfigured non-positive horizon must never wipe the table — skip and alert.
        log.error(
            "snapshot prune SKIPPED: non-positive retention-days={} would drop all history", retentionDays);
        ledger.fail(runId, "non-positive retention-days=" + retentionDays);
        ntfy.send(
            "ArthaYantra snapshot prune misconfigured",
            "urgent",
            "artha.market.snapshot-retention-days=" + retentionDays + " (<= 0) — prune skipped");
        return List.of();
      }
      // Preview first (visibility): which chunks WOULD drop_chunks remove? show_chunks is read-only.
      // Today (capture began 2026-06-15) this is empty, so the pass is a logged, safe no-op.
      List<String> toDrop =
          jdbc.queryForList(
              "SELECT c::text FROM public.show_chunks('" + TABLE
                  + "', older_than => make_interval(days => ?)) AS c",
              String.class,
              retentionDays);
      if (toDrop.isEmpty()) {
        log.info(
            "snapshot prune: no chunks older than {}d in {} — no-op", retentionDays, TABLE);
        ledger.succeed(runId, 0);
        return List.of();
      }
      // Chunk-wise drop via TimescaleDB drop_chunks (compression-safe: whole chunk files are
      // removed with no decompression — NEVER a row-wise DELETE), counted SERVER-SIDE. Called
      // DIRECTLY, NOT via V010's marketdata.prune_options_snapshots wrapper: a LANGUAGE-sql function
      // returning SETOF regclass re-resolves each just-dropped chunk's regclass on return and errors
      // "relation ... does not exist" (reproduced on Timescale 2.17.2). The dropped chunk NAMES come
      // from the pre-drop show_chunks preview (toDrop) — exactly the set drop_chunks removes.
      Long dropped =
          jdbc.queryForObject(
              "SELECT count(*) FROM public.drop_chunks('" + TABLE
                  + "', older_than => make_interval(days => ?))",
              Long.class,
              retentionDays);
      long droppedCount = dropped == null ? toDrop.size() : dropped;
      log.info(
          "snapshot prune: dropped {} chunk(s) older than {}d from {}: {}",
          droppedCount,
          retentionDays,
          TABLE,
          toDrop);
      ledger.succeed(runId, droppedCount);
      return toDrop;
    } catch (RuntimeException e) {
      log.error("snapshot prune FAILED (retention={}d): {}", retentionDays, e.getMessage(), e);
      ledger.fail(runId, e.getMessage());
      ntfy.send(
          "ArthaYantra snapshot prune failed",
          "urgent",
          TABLE + " retention prune (" + retentionDays + "d) errored: " + e.getMessage());
      return List.of();
    }
  }
}
