package in.arthayantra.marketdata.canary;

import in.arthayantra.common.web.time.Ist;
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
 *
 * <p><b>⚠️ The comparison population is BORROWED, and it collapsed once already.</b> This canary
 * never fetched its own Kite bars — it compares whatever {@code source='KITE'} 1d rows some other
 * job happened to leave in {@code candles}, and until 2026-08-10 that was the swing batch fetching
 * bars for the names it was screening at 20:00 ({@code MinerviniSwingScheduler}'s javadoc documents
 * the same dependency from the other side). #1333 moved that batch to a 16:00 EXITS-ONLY settle, so
 * it now fetches only the symbols the book HOLDS. Measured: symbols compared went
 * 171 / 159 / 160 / 164 on 08-05..08-10 and then <b>14 / 14</b> on 08-11 / 08-12 — against an EQ
 * bhavcopy universe of ~2 450. On 2026-08-11 this canary reported <b>GREEN on 14 symbols</b>.
 *
 * <p>That is catalogue trap #14: an armed gate whose operand is structurally near-zero, reporting
 * success. Severity was keyed ONLY on the divergent count against {@link #redFloor}, so a
 * population of one symbol that happened to agree read exactly like a clean universe. Worse, the
 * arithmetic made the top severity unreachable: with 14 comparable symbols, {@code divergent} can
 * never reach a {@code redFloor} of 20, so RED was not merely unlikely but impossible.
 *
 * <p>{@code artha.bhavcopy-close.min-compared} is the alarm for that: below it the verdict can
 * never be GREEN. It is an ABSOLUTE count on purpose — expressing it as a fraction of the bhavcopy
 * population would make the floor itself collapse whenever the bhavcopy ingest is the thing that
 * failed (0 of 0 is trivially 100% covered), which rebuilds the same trap one level up. The floor
 * only ESCALATES: it can turn GREEN into YELLOW, never soften a divergence verdict.
 *
 * <p><b>The floor is the alarm, not the cure.</b> Making this canary fetch its own population is
 * the real fix and is deliberately NOT in this change — see the note on {@link #minCompared}.
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

  /**
   * The report for a trade date: compared count, divergent count, and the worst offenders.
   *
   * <p>{@code minCompared} is the coverage floor in force, echoed so the report explains its own
   * verdict: a {@code YELLOW} carrying {@code divergent=0} is a COVERAGE failure, and without the
   * floor beside the count a reader cannot tell that from a clean run.
   */
  public record BhavcopyCloseReport(
      @Schema(types = {"string", "null"}) LocalDate tradeDate,
      String status,
      int compared,
      int divergent,
      int minCompared,
      @Schema(type = "string") BigDecimal thresholdPct,
      List<CloseMismatch> offenders) {}

  // Symbols whose 1d bar is genuinely Kite-captured (source='KITE'); a bhavcopy-projected bar
  // (source='BHAVCOPY') would compare against itself. Matches the two closes on the IST session date.
  //
  // ⚠️ `b.series = 'EQ'` is KEPT, deliberately, and it was checked rather than assumed — an EQ-only
  // filter elsewhere in this repo once hid every BE-series symbol and manufactured a fake outage.
  // Measured over 2026-08-05..08-12, this filter drops exactly ZERO rows from the comparison: the
  // same join counted EQ-only, EQ+BE, and series-agnostic returns an identical
  // 171 / 159 / 160 / 164 / 14 / 14 on every one of the six dates, because every symbol carrying a
  // Kite 1d bar is EQ. So it is not what narrowed the population and widening it would buy nothing
  // today. It IS a latent narrowing: the moment a BE-series name is held (the bars come from the
  // swing book), its close would silently stop being compared. Left as-is because changing the
  // divergence population is not this change's business, and the coverage floor below now makes any
  // future shrink from this cause visible instead of silent.
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
   * The smallest comparison population this canary will certify. Below it the verdict is never
   * GREEN.
   *
   * <p><b>Why 100.</b> Measured {@code compared} counts: 171 (08-05), 159 (08-06), 160 (08-07), 164
   * (08-10), then 14 (08-11), 14 (08-12). 100 sits ~1.6× below the healthy minimum — so an ordinary
   * shrink in whatever job leaves the bars behind does not cry wolf — and ~7× above the collapsed
   * value, so the collapse cannot hide under it. The exact number is not delicate: the two
   * populations are an order of magnitude apart, so anything from ~30 to ~150 separates them
   * identically, and 100 is simply the round middle of that range.
   *
   * <p><b>⚠️ This fires YELLOW every session until the borrowed population is fixed, and that is
   * the intent, not an oversight.</b> Sizing the floor to pass today's 14 would be choosing the
   * threshold to fit the broken state — the trap this property exists to close.
   *
   * <p><b>Why not fetch our own bars instead.</b> That is the real fix and it is out of scope here,
   * stated rather than quietly skipped. A full-universe Kite 1d fetch is ~2 450 symbols against a
   * 3 req/s historical limit — ~14 minutes of sustained calls on the limiter the live feed shares,
   * daily, to serve a canary. A fixed representative sample (say the NIFTY 200, ~67 s) is the
   * plausible middle and is a genuinely better design, but it is a new fetch path with its own
   * scheduling, rate-limiter contention and failure modes — a larger change that deserves its own
   * PR and its own review. Shipping the floor first means the next session cannot pass silently
   * while that is decided, which is the property actually worth having today.
   */
  private final int minCompared;

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
      @Value("${artha.bhavcopy-close.sample-limit:25}") int sampleLimit,
      @Value("${artha.bhavcopy-close.min-compared:100}") int minCompared) {
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
    this.minCompared = minCompared;
  }

  /**
   * Daily sweep, after the evening's bhavcopy lands. Live-only.
   *
   * <p>⚠️ Sweeps the LATEST bhavcopy date, which is not necessarily today's. NSE's publish time
   * varies — measured 17:52, 17:59, 18:47 and 19:31 across four days — so on a late night the file
   * has not landed when this fires, and without the guard below the canary silently re-evaluates
   * YESTERDAY's session and alerts on it as if it were tonight's: an operator message naming the
   * wrong day, repeated every late night — and a confidently wrong alert trains the owner to ignore
   * the channel.
   *
   * <p>⚠️ A skipped comparison is PERMANENTLY missed for that session, not deferred.
   * {@code BhavcopyStartupCatchup} starts the backfill on the next boot, so the DATA arrives — but
   * this canary has no completion listener and only fires on its own cron, and by the time it next
   * fires that session is no longer today, so the guard below skips it again. Forever. Skipping
   * still trades a missed check for a WRONG one, which is the right trade; it does not make the
   * check free, and the loss is a whole session's close comparison rather than a delay.
   *
   * <p><b>20:10 → 18:58, and this LOSES coverage. Stated because it is a real cost of moving the
   * chain inside the 19:00 machine-off boundary, not a wash.</b> Against the four measured publish
   * times, 20:10 caught all four; 18:58 catches three (17:52, 17:59, 18:47) and misses 19:31. It is
   * the last free minute before shutdown, so it is the most coverage the window allows — 18:52,
   * seven minutes after an ASYNCHRONOUS 18:45 submit, would have caught only two.
   *
   * <p>The 19:31 case needs a bhavcopy-completion listener, and that was BUILT AND WITHDRAWN from
   * the schedule PR rather than shipped half-right. Three review rounds found successive defects in
   * it: routed through the date guard it was meant to bypass, so it recovered nothing; then a
   * dedupe that covered only the rare trigger order; then — decisively — the watermark is
   * in-memory, so the daily JVM restart replays the startup catch-up with a null watermark and
   * re-alerts yesterday's divergence every morning. Doing it properly needs a PERSISTED compared-
   * session watermark, which is its own change with its own migration.
   */
  @Scheduled(cron = "${artha.bhavcopy-close.cron:0 58 18 * * MON-FRI}", zone = "Asia/Kolkata")
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
      // Zero compared symbols is the smallest population there is, so it goes through the same
      // floor as any other: an empty table is the one case where "nothing diverged" is guaranteed
      // and means nothing at all. This used to return GREEN.
      return report(null, List.of(), 0);
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
    return report(tradeDate, offenders, compared == null ? 0 : compared);
  }

  /**
   * Builds the report, applying the divergence rule first and the coverage floor on top of it.
   *
   * <p>⚠️ The floor is applied as a ONE-WAY escalation, never as a replacement. Computing the
   * status as "short coverage ⇒ YELLOW, else the divergence rule" reads equivalent and is not: it
   * would DOWNGRADE a RED to a YELLOW exactly when the population is small, i.e. silence the worst
   * verdict in the situation the floor exists to flag. Coverage may only turn GREEN into YELLOW.
   */
  private BhavcopyCloseReport report(
      LocalDate tradeDate, List<CloseMismatch> offenders, int comparedCount) {
    int divergent = offenders.size();
    String byDivergence = divergent == 0 ? GREEN : divergent >= redFloor ? RED : YELLOW;
    String status =
        comparedCount < minCompared && GREEN.equals(byDivergence) ? YELLOW : byDivergence;
    List<CloseMismatch> sample =
        offenders.size() > sampleLimit ? offenders.subList(0, sampleLimit) : offenders;
    return new BhavcopyCloseReport(
        tradeDate,
        status,
        comparedCount,
        divergent,
        minCompared,
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
    if (report.divergent() == 0) {
      // Coverage is the ONLY reason this is not GREEN, so the operator message must say so.
      // Reusing the divergence wording here would page about "0 of 14 symbols diverge", which
      // describes a clean run and buries the actual finding.
      String message =
          "only " + report.compared() + " symbols had both a bhavcopy row and a Kite 1d bar on "
              + report.tradeDate() + " (floor " + report.minCompared() + ") — nothing diverged, but"
              + " that population is too small to certify the close feed, so this is NOT a clean"
              + " run";
      log.warn("bhavcopy-close canary YELLOW (coverage): {}", message);
      sendAlert("ArthaYantra bhavcopy-close coverage", "default", message);
      return;
    }
    boolean coverageShort = report.compared() < report.minCompared();
    String samples =
        report.offenders().stream()
            .map(o -> o.symbol() + " (bhav " + o.bhavClose() + " vs kite " + o.kiteClose() + ", " + o.relDiffPct() + "%)")
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
    String message =
        report.divergent() + " of " + report.compared() + " symbols diverge > "
            + report.thresholdPct() + "% on " + report.tradeDate() + ": " + samples
            + (coverageShort
                ? " — and only " + report.compared() + " symbols were comparable (floor "
                    + report.minCompared() + "), so this rate is measured on a population too small"
                    + " to certify the feed either way"
                : "");
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
