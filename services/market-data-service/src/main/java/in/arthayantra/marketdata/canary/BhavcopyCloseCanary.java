package in.arthayantra.marketdata.canary;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.ingest.BhavcopyBackfillCompleted;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bhavcopy-close vs Kite-1d-close data-quality canary (app-platform audit 2026-07-10 §8 V8). The
 * corporate-action job diffs Kite-vs-Kite closes only and excludes bhavcopy-only names, so a
 * divergence between the NSE EOD bhavcopy close and the Kite-captured 1d close for a symbol that has
 * BOTH — an unadjusted corporate action, or a bad print in one feed — is never surfaced. This
 * standalone canary compares the two closes for the latest bhavcopy trade date (only symbols whose
 * {@code candles} 1d bar carries {@code source='KITE'}, i.e. genuinely dual-sourced) and alerts on the
 * count exceeding the threshold. Read-only; it deliberately does NOT touch the corporate-action plane.
 *
 * <p>Keying: bhavcopy close is {@code nse_eod_bhavcopy.close_price} (PK {@code trade_date, symbol,
 * series}); the Kite close is a {@code candles} 1d row (PK excludes {@code source}) filtered to {@code
 * source='KITE'} and matched on the IST calendar date of the bucket. Live-only sweep + fail-soft
 * alerting; {@link #evaluate(LocalDate)} is side-effect-free so a GET / a test can call it any time.
 */
@Component
public class BhavcopyCloseCanary {

  private static final Logger log = LoggerFactory.getLogger(BhavcopyCloseCanary.class);
  private static final String GREEN = "GREEN";
  private static final String YELLOW = "YELLOW";
  private static final String RED = "RED";

  /** One symbol whose bhavcopy close diverges from its Kite 1d close beyond the threshold. */
  public record CloseMismatch(
      String symbol, @Schema(type = "string") BigDecimal bhavClose, @Schema(type = "string") BigDecimal kiteClose, @Schema(type = "string") BigDecimal relDiffPct) {}

  /** The report for a trade date: compared count, divergent count, and the worst offenders. */
  public record BhavcopyCloseReport(
      @Schema(types = {"string", "null"}) LocalDate tradeDate,
      String status,
      int compared,
      int divergent,
      @Schema(type = "string") BigDecimal thresholdPct,
      List<CloseMismatch> offenders) {}

  // Symbols whose 1d bar is genuinely Kite-captured (source='KITE'); a bhavcopy-projected bar
  // (source='BHAVCOPY') would compare against itself. Matches the two closes on the IST session date.
  private static final String COMPARE_SQL =
      """
      SELECT b.symbol,
             b.close_price AS bhav_close,
             c.close AS kite_close,
             abs(b.close_price - c.close) / c.close AS rel_diff
      FROM nse_eod_bhavcopy b
      JOIN candles c
        ON c.exchange = 'NSE' AND c.tradingsymbol = b.symbol AND c.interval = '1d'
       AND c.source = 'KITE'
       AND (c.bucket AT TIME ZONE 'Asia/Kolkata')::date = b.trade_date
      WHERE b.series = 'EQ' AND b.trade_date = ?
        AND b.close_price IS NOT NULL AND c.close IS NOT NULL AND c.close > 0
        AND abs(b.close_price - c.close) / c.close > ?
      ORDER BY rel_diff DESC
      """;

  private final JdbcTemplate jdbc;
  private final NtfyClient ntfy;
  private final Clock clock;
  private final Counter divergenceCounter;
  private final boolean live;
  private final boolean enabled;
  private final boolean alertsEnabled;
  private final BigDecimal threshold;
  private final int redFloor;
  private final int sampleLimit;

  /**
   * The last trade date CLAIMED for comparison, so a session is compared exactly once however the two
   * entry points interleave.
   *
   * <p>⚠️ An {@code AtomicReference} with a compare-and-set claim, not a {@code volatile} write after
   * the fact. The first version wrote it at the END of {@code sweep()} and checked it only in the
   * listener, which suppressed cron-then-event and nothing else — and cross-vendor review pointed out
   * that production's COMMON order is the opposite: the file usually lands before 18:52, so the event
   * runs first and the cron then compares the same session again. Two entry points needing
   * exactly-once means a claim in the shared path, which is what {@link #compareOnce} is.
   */
  private final AtomicReference<LocalDate> lastSwept = new AtomicReference<>();

  public BhavcopyCloseCanary(
      JdbcTemplate jdbc,
      NtfyClient ntfy,
      Clock clock,
      MeterRegistry meterRegistry,
      Environment environment,
      @Value("${artha.bhavcopy-close.enabled:true}") boolean enabled,
      @Value("${artha.bhavcopy-close.alerts-enabled:true}") boolean alertsEnabled,
      @Value("${artha.bhavcopy-close.threshold:0.01}") BigDecimal threshold,
      @Value("${artha.bhavcopy-close.red-floor:20}") int redFloor,
      @Value("${artha.bhavcopy-close.sample-limit:25}") int sampleLimit) {
    this.jdbc = jdbc;
    this.ntfy = ntfy;
    this.clock = clock;
    this.divergenceCounter = meterRegistry.counter("ay_bhavcopy_close_divergence_total");
    this.live = environment.matchesProfiles("live");
    this.enabled = enabled;
    this.alertsEnabled = alertsEnabled;
    this.threshold = threshold;
    this.redFloor = redFloor;
    this.sampleLimit = sampleLimit;
  }

  /**
   * Daily sweep, after the evening's bhavcopy lands. Live-only.
   *
   * <p>⚠️ Sweeps the LATEST bhavcopy date, which is not necessarily today's. NSE's publish time
   * varies — measured 17:52, 17:59, 18:47 and 19:31 across four days — so on a late night the file
   * has not landed when this fires, and without the guard below the canary silently re-evaluates
   * YESTERDAY's session and alerts on it as if it were tonight's: an operator message naming the
   * wrong day, repeated every late night — and a confidently wrong alert trains the owner to ignore
   * the channel. The margin is thin even at the current 20:10 against a 19:30 bhavcopy, and it
   * shrinks to nothing under the pending schedule move.
   *
   * <p>⚠️ A skipped comparison used to be PERMANENTLY missed for that session, not deferred: by the
   * time the cron next fired, that session was no longer today and the guard skipped it again,
   * forever. At 20:10 against a 19:30 publish the margin was thin; moving the chain inside the 19:00
   * machine-off boundary put the cron at 18:52 against an 18:45 ASYNCHRONOUS submit, which would have
   * made the miss the normal case rather than the exception. {@link #onBhavcopyCompleted} closes it —
   * see there for why the guard above is what makes the listener safe.
   */
  @Scheduled(cron = "${artha.bhavcopy-close.cron:0 52 18 * * MON-FRI}", zone = "Asia/Kolkata")
  public void sweep() {
    if (!live || !enabled) {
      return;
    }
    LocalDate latest = latestTradeDate();
    if (latest == null) {
      return;
    }
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    if (latest.isBefore(today)) {
      // Neutral wording on purpose: this also fires on a weekday NSE holiday, where there is no
      // file to wait for and "has not landed yet" would be a small lie in the log every holiday.
      log.info(
          "bhavcopy-close canary skipped — the newest bhavcopy is {} but today is {}; there is no"
              + " bar for today (a late publish, or a non-trading weekday), and comparing an older"
              + " session would alert on the wrong day",
          latest, today);
      return;
    }
    compareOnce(latest);
  }

  /**
   * Second entry point: run the comparison the moment tonight's bhavcopy actually lands.
   *
   * <p>The cron alone cannot work here. {@code BhavcopyBackfillService} submits ASYNCHRONOUSLY and
   * returns immediately, and NSE's publish time was measured at 17:52, 17:59, 18:47 and 19:31 across
   * four days — so a fixed minute seven minutes after the submit is a coin flip at best. This makes
   * the trigger the event it was always waiting for.
   *
   * <p><b>It is deliberately not a second unconditional run.</b> Completion is published even when
   * both exchanges returned nothing, so the event does NOT prove today's file arrived — the
   * {@code latest.isBefore(today)} guard in {@link #sweep} is what makes it safe, and an empty
   * completion simply skips exactly as the cron would. {@code lastSwept} then stops the pair
   * double-alerting when the file lands BEFORE the cron minute, which is the common case.
   *
   * <p>This also recovers the late-publish night for free: {@code BhavcopyStartupCatchup} replays the
   * backfill on the next boot, that replay publishes completion, and the canary now hears it.
   */
  @EventListener(BhavcopyBackfillCompleted.class)
  void onBhavcopyCompleted() {
    if (!live || !enabled) {
      return;
    }
    // ⚠️ Guard FIRST, then wrap everything, and never rethrow. Spring multicasts this event
    // SYNCHRONOUSLY and BhavcopyBackfillService catches only around the whole multicast, so an
    // exception escaping this observer can stop Minervini and Manas from ever receiving completion.
    // A canary must not be able to break the chain it observes.
    try {
      LocalDate latest = latestTradeDate();
      if (latest == null) {
        return;
      }
      // NO today-guard here, and that is the point of this path rather than an oversight. The cron
      // is blind — it fires at a fixed minute and cannot know whether anything landed, so for IT a
      // pre-today `latest` means "tonight's file has not arrived" and comparing it would be a
      // wrong-day verdict. This path only runs BECAUSE a fetch just completed, so a `latest` of
      // yesterday means the late-published session has now genuinely arrived and is the one to
      // compare. That is what recovers the 19:31 night: the machine is off before the file lands,
      // the next morning's BhavcopyStartupCatchup replays it, completion fires, and this compares
      // the session the cron would have skipped forever. The alert names `report.tradeDate()`, so
      // reporting a prior session is explicit rather than mislabelled as tonight's.
      compareOnce(latest);
    } catch (RuntimeException e) {
      log.warn("bhavcopy-close canary listener failed (non-fatal to the chain): {}", e.getMessage());
    }
  }

  /**
   * Compare {@code session} at most once across both entry points, then publish.
   *
   * <p>The CAS is the exactly-once guarantee: whichever of the cron and the listener claims the
   * session runs the comparison, and the other returns having done nothing. A failed evaluation
   * releases the claim so the next trigger can retry rather than the session being silently consumed
   * by an attempt that produced no verdict.
   */
  private void compareOnce(LocalDate session) {
    LocalDate previous = lastSwept.get();
    if (session.equals(previous) || !lastSwept.compareAndSet(previous, session)) {
      return;
    }
    BhavcopyCloseReport report;
    try {
      report = evaluate(session);
    } catch (RuntimeException e) {
      lastSwept.compareAndSet(session, previous);
      log.warn("bhavcopy-close canary failed for {}: {}", session, e.getMessage());
      return;
    }
    publish(report);
  }

  /** The newest EQ bhavcopy trade date, or {@code null} when the table is empty. */
  public LocalDate latestTradeDate() {
    return jdbc.query(
        "SELECT max(trade_date) AS d FROM nse_eod_bhavcopy WHERE series = 'EQ'",
        rs -> rs.next() ? rs.getObject("d", LocalDate.class) : null);
  }

  /** Compares the two closes for {@code tradeDate}. Side-effect-free (the GET + tests call this). */
  public BhavcopyCloseReport evaluate(LocalDate tradeDate) {
    if (tradeDate == null) {
      return new BhavcopyCloseReport(
          null, GREEN, 0, 0,
          threshold.multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP),
          List.of());
    }
    List<CloseMismatch> offenders =
        jdbc.query(
            COMPARE_SQL,
            (rs, n) ->
                new CloseMismatch(
                    rs.getString("symbol"),
                    rs.getBigDecimal("bhav_close"),
                    rs.getBigDecimal("kite_close"),
                    rs.getBigDecimal("rel_diff")
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, java.math.RoundingMode.HALF_UP)),
            java.sql.Date.valueOf(tradeDate),
            threshold);
    Integer compared =
        jdbc.queryForObject(
            "SELECT count(*) FROM nse_eod_bhavcopy b "
                + "JOIN candles c ON c.exchange='NSE' AND c.tradingsymbol=b.symbol "
                + " AND c.interval='1d' AND c.source='KITE' "
                + " AND (c.bucket AT TIME ZONE 'Asia/Kolkata')::date = b.trade_date "
                + "WHERE b.series='EQ' AND b.trade_date=? AND b.close_price IS NOT NULL "
                + " AND c.close IS NOT NULL AND c.close > 0",
            Integer.class,
            java.sql.Date.valueOf(tradeDate));
    int divergent = offenders.size();
    int comparedCount = compared == null ? 0 : compared;
    String status = divergent == 0 ? GREEN : divergent >= redFloor ? RED : YELLOW;
    List<CloseMismatch> sample =
        offenders.size() > sampleLimit ? offenders.subList(0, sampleLimit) : offenders;
    return new BhavcopyCloseReport(
        tradeDate,
        status,
        comparedCount,
        divergent,
        threshold.multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP),
        List.copyOf(sample));
  }

  private void publish(BhavcopyCloseReport report) {
    if (GREEN.equals(report.status())) {
      log.info(
          "bhavcopy-close canary GREEN for {} — {} symbols compared, none diverge > {}%",
          report.tradeDate(), report.compared(), report.thresholdPct());
      return;
    }
    divergenceCounter.increment(report.divergent());
    String samples =
        report.offenders().stream()
            .map(o -> o.symbol() + " (bhav " + o.bhavClose() + " vs kite " + o.kiteClose() + ", " + o.relDiffPct() + "%)")
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
    String message =
        report.divergent() + " of " + report.compared() + " symbols diverge > "
            + report.thresholdPct() + "% on " + report.tradeDate() + ": " + samples;
    if (RED.equals(report.status())) {
      log.error("bhavcopy-close canary RED: {}", message);
      sendAlert("ArthaYantra bhavcopy-close divergence", "urgent", message);
    } else {
      log.warn("bhavcopy-close canary YELLOW: {}", message);
      sendAlert("ArthaYantra bhavcopy-close divergence", "default", message);
    }
  }

  private void sendAlert(String title, String priority, String message) {
    if (alertsEnabled) {
      ntfy.send(title, priority, message);
    }
  }
}
