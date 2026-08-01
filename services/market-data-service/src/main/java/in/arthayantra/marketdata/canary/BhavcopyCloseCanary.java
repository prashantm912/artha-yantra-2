package in.arthayantra.marketdata.canary;

import in.arthayantra.marketdata.alerts.NtfyClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

  /** Daily sweep (20:10 IST, after the ~19:30 bhavcopy lands). Live-only. */
  @Scheduled(cron = "${artha.bhavcopy-close.cron:0 10 20 * * MON-FRI}", zone = "Asia/Kolkata")
  public void sweep() {
    if (!live || !enabled) {
      return;
    }
    LocalDate latest = latestTradeDate();
    if (latest == null) {
      return;
    }
    BhavcopyCloseReport report;
    try {
      report = evaluate(latest);
    } catch (RuntimeException e) {
      log.warn("bhavcopy-close canary failed: {}", e.getMessage());
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
