package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.options.OiInterval;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cross-source OI divergence canary (app-platform audit 2026-07-10 §8 V7). The two OI histories that
 * exist since live capture began (2026-06-15) — the captured {@code options_chain_snapshots} and the
 * per-contract {@code candles} pivoted by {@link CandleDerivedChainReader} — are never compared, so a
 * systematic drift between the live snapshot feed and the Upstox candle backfill would go unnoticed.
 * This weekly canary picks the most recently EXPIRED expiry that has snapshot data (both sources exist
 * for a completed expiry), reads each source's per-strike OI over the expiry's tail through the SAME
 * reader bucketing (so the comparison is apples-to-apples), and reports the per-bucket total-OI
 * divergence — alerting when it exceeds the threshold.
 *
 * <p>Lives in the options module so it reuses both readers directly (identical bucket semantics)
 * rather than duplicating their SQL. Keys the cross-source bucket alignment by {@link
 * OffsetDateTime#toInstant()} — never the offset-bearing {@code OffsetDateTime} (the #214 timestamp-
 * key trap: the two sources can carry different UTC offsets). Live-only sweep + fail-soft alerting;
 * {@link #evaluate()} is side-effect-free so a GET / a test can call it any time.
 */
@Component
public class CrossSourceOiCanary {

  private static final Logger log = LoggerFactory.getLogger(CrossSourceOiCanary.class);
  private static final String GREEN = "GREEN";
  private static final String YELLOW = "YELLOW";
  private static final String RED = "RED";
  private static final OiInterval INTERVAL = OiInterval.M60;

  /** One underlying's cross-source verdict for its most recent expired expiry. */
  public record UnderlyingDivergence(
      String underlying,
      @Schema(types = {"string", "null"}) LocalDate expiry,
      String status,
      String detail,
      int bucketsCompared,
      @Schema(types = {"number", "null"}) BigDecimal meanDivergencePct,
      @Schema(types = {"number", "null"}) BigDecimal maxDivergencePct) {}

  /** The whole report: worst-of the per-underlying statuses. */
  public record CrossSourceReport(
      String status, Instant asOf, List<UnderlyingDivergence> underlyings) {}

  private final OptionsSnapshotReader snapshotReader;
  private final CandleDerivedChainReader candleReader;
  private final JdbcTemplate jdbc;
  private final NtfyClient ntfy;
  private final Clock clock;
  private final Counter divergenceCounter;
  private final boolean live;
  private final boolean enabled;
  private final boolean alertsEnabled;
  private final List<String> underlyings;
  private final int lookbackDays;
  private final BigDecimal threshold;
  private final long minTotalOi;

  public CrossSourceOiCanary(
      OptionsSnapshotReader snapshotReader,
      CandleDerivedChainReader candleReader,
      JdbcTemplate jdbc,
      NtfyClient ntfy,
      Clock clock,
      MeterRegistry meterRegistry,
      Environment environment,
      @Value("${artha.oi-cross-source.enabled:true}") boolean enabled,
      @Value("${artha.oi-cross-source.alerts-enabled:true}") boolean alertsEnabled,
      @Value("${artha.oi-cross-source.underlyings:NIFTY 50}") List<String> underlyings,
      @Value("${artha.oi-cross-source.lookback-days:10}") int lookbackDays,
      @Value("${artha.oi-cross-source.divergence-threshold:0.15}") BigDecimal threshold,
      @Value("${artha.oi-cross-source.min-total-oi:100000}") long minTotalOi) {
    this.snapshotReader = snapshotReader;
    this.candleReader = candleReader;
    this.jdbc = jdbc;
    this.ntfy = ntfy;
    this.clock = clock;
    this.divergenceCounter = meterRegistry.counter("ay_oi_cross_source_divergence_total");
    this.live = environment.matchesProfiles("live");
    this.enabled = enabled;
    this.alertsEnabled = alertsEnabled;
    this.underlyings = underlyings;
    this.lookbackDays = lookbackDays;
    this.threshold = threshold;
    this.minTotalOi = minTotalOi;
  }

  /** Weekly sweep (Sunday 07:00 IST). Live-only; the audited sources are only populated live. */
  @Scheduled(cron = "${artha.oi-cross-source.cron:0 0 7 * * SUN}", zone = "Asia/Kolkata")
  public void sweep() {
    if (!live || !enabled) {
      return;
    }
    CrossSourceReport report;
    try {
      report = evaluate();
    } catch (RuntimeException e) {
      log.warn("cross-source OI canary failed: {}", e.getMessage());
      return;
    }
    publish(report);
  }

  /** Compares each configured underlying's snapshot vs candle OI. Side-effect-free. */
  public CrossSourceReport evaluate() {
    List<UnderlyingDivergence> results = new ArrayList<>();
    for (String underlying : underlyings) {
      results.add(compare(underlying.trim()));
    }
    String overall =
        results.stream().anyMatch(r -> RED.equals(r.status()))
            ? RED
            : results.stream().anyMatch(r -> YELLOW.equals(r.status())) ? YELLOW : GREEN;
    return new CrossSourceReport(overall, clock.instant(), List.copyOf(results));
  }

  private UnderlyingDivergence compare(String underlying) {
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    LocalDate expiry =
        jdbc.query(
            "SELECT max(expiry) AS e FROM options_chain_snapshots WHERE underlying = ? AND expiry < ?",
            rs -> rs.next() ? rs.getObject("e", LocalDate.class) : null,
            underlying,
            java.sql.Date.valueOf(today));
    if (expiry == null) {
      return yellow(underlying, null, "no expired expiry with snapshot data");
    }
    String expiredName = CandleDerivedChainReader.expiredUnderlying(underlying);
    if (expiredName == null) {
      return yellow(underlying, expiry, "no expired-contracts underlying mapping");
    }
    OffsetDateTime from = expiry.minusDays(lookbackDays).atStartOfDay().atOffset(Ist.OFFSET);
    OffsetDateTime to = expiry.plusDays(1).atStartOfDay().atOffset(Ist.OFFSET);

    Map<Instant, Long> snapTotals = totalOiByBucket(snapshotReader.series(underlying, expiry, INTERVAL, from, to));
    Map<Instant, Long> candTotals = totalOiByBucket(candleReader.series(expiredName, expiry, INTERVAL, from, to));
    if (snapTotals.isEmpty() || candTotals.isEmpty()) {
      return yellow(
          underlying, expiry,
          "insufficient overlap (snapshot buckets=" + snapTotals.size()
              + ", candle buckets=" + candTotals.size() + ")");
    }

    BigDecimal sumDiv = BigDecimal.ZERO;
    BigDecimal maxDiv = BigDecimal.ZERO;
    int compared = 0;
    for (Map.Entry<Instant, Long> e : snapTotals.entrySet()) {
      Long candTotal = candTotals.get(e.getKey());
      if (candTotal == null) {
        continue; // only buckets present in BOTH sources are comparable
      }
      long denom = Math.max(candTotal, minTotalOi);
      BigDecimal div =
          BigDecimal.valueOf(Math.abs(e.getValue() - candTotal))
              .divide(BigDecimal.valueOf(denom), 6, RoundingMode.HALF_UP);
      sumDiv = sumDiv.add(div);
      if (div.compareTo(maxDiv) > 0) {
        maxDiv = div;
      }
      compared++;
    }
    if (compared == 0) {
      return yellow(underlying, expiry, "no common buckets between the two sources");
    }
    BigDecimal mean = sumDiv.divide(BigDecimal.valueOf(compared), 6, RoundingMode.HALF_UP);
    BigDecimal meanPct = mean.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    BigDecimal maxPct = maxDiv.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    if (mean.compareTo(threshold) > 0) {
      return new UnderlyingDivergence(
          underlying, expiry, RED,
          "mean OI divergence " + meanPct + "% over " + compared + " bucket(s) exceeds "
              + threshold.multiply(BigDecimal.valueOf(100)) + "%",
          compared, meanPct, maxPct);
    }
    return new UnderlyingDivergence(
        underlying, expiry, GREEN,
        "mean OI divergence " + meanPct + "% over " + compared + " bucket(s)", compared, meanPct, maxPct);
  }

  /** Sums OI across strikes per bucket, keyed by INSTANT (never the offset-bearing OffsetDateTime). */
  private static Map<Instant, Long> totalOiByBucket(List<OptionsSnapshotReader.StrikePoint> points) {
    Map<Instant, Long> totals = new TreeMap<>();
    for (OptionsSnapshotReader.StrikePoint p : points) {
      if (p.bucket() == null || p.oi() == null) {
        continue;
      }
      totals.merge(p.bucket().toInstant(), p.oi(), Long::sum);
    }
    return new LinkedHashMap<>(totals);
  }

  private void publish(CrossSourceReport report) {
    List<UnderlyingDivergence> problems =
        report.underlyings().stream().filter(u -> !GREEN.equals(u.status())).toList();
    if (problems.isEmpty()) {
      log.info("cross-source OI canary GREEN — all underlyings within {} divergence", threshold);
      return;
    }
    boolean anyRed = problems.stream().anyMatch(u -> RED.equals(u.status()));
    String message =
        problems.stream()
            .map(u -> u.underlying() + " " + u.expiry() + ": " + u.status() + " — " + u.detail())
            .reduce((a, b) -> a + "\n" + b)
            .orElse("");
    if (anyRed) {
      divergenceCounter.increment();
      log.error("cross-source OI divergence RED: {}", message.replace('\n', ';'));
      sendAlert("ArthaYantra OI cross-source divergence", "urgent", message);
    } else {
      log.warn("cross-source OI canary YELLOW: {}", message.replace('\n', ';'));
    }
  }

  private void sendAlert(String title, String priority, String message) {
    if (alertsEnabled) {
      ntfy.send(title, priority, message);
    }
  }

  private static UnderlyingDivergence yellow(String underlying, LocalDate expiry, String detail) {
    return new UnderlyingDivergence(underlying, expiry, YELLOW, detail, 0, null, null);
  }
}
