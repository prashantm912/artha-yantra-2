package in.arthayantra.marketdata.ingest;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Closes out {@code marketdata.ingest_runs} rows left {@code RUNNING} by a process that died before
 * it could stamp them (ledger H12, second half).
 *
 * <p><b>The failure this exists for.</b> {@link IngestRunLedger} deliberately leaves a {@code RUNNING}
 * row behind when a job crashes — that is a stall signal, not a bug. But a job killed by a CONTAINER
 * RECREATE leaves the same row with nothing left alive to finish it, and no reaper existed: on
 * 2026-08-11 a deploy at 18:00 IST killed {@code MANAS_SCREEN} 28 s in, and its row stayed
 * {@code RUNNING} with a NULL {@code finished_at} until it was cleared by hand. Any check asking "is
 * the latest run for this source terminal?" reads such a row as in-flight <b>forever</b>.
 *
 * <p>It is not hypothetical maintenance: the 2026-08-12 schedule move puts the evening chain at
 * 18:05–18:57, which is nearer the post-close deploy slot than the old 19:00–21:20 chain was, so a
 * deploy landing inside a job's execution window became MORE likely, not less.
 *
 * <p><b>Why "started before this process booted" is the exact predicate.</b> A {@code RUNNING} row
 * that predates our own boot cannot belong to us — whatever wrote it is gone. Anything this process
 * starts afterwards is untouched, however long it runs, so a genuinely slow ingest is never reaped
 * out from under itself. That is a stronger rule than an age threshold, which would have to guess how
 * long is too long and would race a legitimately long backfill.
 *
 * <p>⚠️ <b>{@code finished_at} is set to now(), and that timestamp is a lie either way — the honest
 * one.</b> The true finish time is unknowable: the process died without recording it. Stamping
 * {@code started_at} would fabricate a plausible zero-duration run; stamping {@code now()} yields an
 * obviously-wrong long one that no reader will mistake for a real measurement, and the {@code error}
 * text says so outright. Loud-wrong over quietly-wrong. Anything computing ingest DURATIONS should
 * exclude rows whose error begins with the marker below.
 *
 * <p>Fail-soft like every other write in this package: a reaper that throws on boot would take the
 * service down over a bookkeeping row.
 */
@Component
public class IngestRunReaper {

  private static final Logger log = LoggerFactory.getLogger(IngestRunReaper.class);

  /** Prefix on the reaped row's {@code error}, so duration readers can exclude these deterministically. */
  public static final String REAPED_MARKER = "REAPED_ON_BOOT";

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public IngestRunReaper(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * Reap on startup, once. Bound to {@link ApplicationReadyEvent} rather than a cron because the
   * condition it repairs is created by a restart and can only be created by one — a periodic sweep
   * would add a way to reap a live run for no extra coverage.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void reapOnBoot() {
    reap(OffsetDateTime.now(clock));
  }

  /**
   * Mark every {@code RUNNING} row started before {@code bootedAt} as {@code FAILURE}. Returns how
   * many were reaped (0 on the normal clean boot). Package-visible so the IT can pin an explicit
   * boundary instead of racing the real clock.
   */
  int reap(OffsetDateTime bootedAt) {
    try {
      int reaped =
          jdbc.update(
              """
              UPDATE ingest_runs
                 SET status = 'FAILURE',
                     finished_at = now(),
                     error = ?
               WHERE status = 'RUNNING'
                 AND started_at < ?
              """,
              REAPED_MARKER
                  + ": still RUNNING when the service booted at "
                  + bootedAt
                  + ", so the process that owned it is gone (a container recreate or crash mid-run)."
                  + " The true finish time is unknown and finished_at is the reap time, not the end"
                  + " of the work — exclude these rows from any duration measurement.",
              bootedAt);
      if (reaped > 0) {
        // WARN, not INFO: a reaped row means a job was killed mid-flight, and whatever it was
        // writing may be half-written. The count is the operator's cue to check that source.
        log.warn(
            "ingest_runs reaper: closed {} row(s) left RUNNING by a previous process — check those"
                + " sources for partial writes",
            reaped);
      } else {
        log.info("ingest_runs reaper: no stranded RUNNING rows at boot");
      }
      return reaped;
    } catch (RuntimeException e) {
      log.warn("ingest_runs reaper failed — non-fatal, rows stay RUNNING: {}", e.getMessage());
      return 0;
    }
  }
}
