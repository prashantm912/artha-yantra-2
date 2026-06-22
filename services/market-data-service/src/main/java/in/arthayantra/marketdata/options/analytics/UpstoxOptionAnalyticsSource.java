package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.upstox.UpstoxAnalyticsClient;
import in.arthayantra.marketdata.upstox.UpstoxOptionAnalytics;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Upstox-sourced PCR + max-pain for the OI-Statistics header (ADR-0002, owner-requested move). Bound
 * ONLY when {@code artha.marketdata.source.optionanalytics=upstox}; the {@code
 * OptionsAnalyticsController} consults it via an {@code ObjectProvider} (absent ⇒ native), so the
 * native band-computed values are the default + the transparent fallback.
 *
 * <p>Why Upstox: native PCR is computed over our captured ATM strike band, which omits deep-OTM
 * strikes Upstox's FULL chain includes (verified ~0.1–1.5% low vs Upstox; max-pain matched exactly).
 * Upstox is the full-chain source of record. REQUIRES {@code artha.upstox.analytics.enabled=true}
 * (consumes {@link UpstoxAnalyticsClient}). {@code ceOi}/{@code peOi} stay native — Upstox exposes
 * only the ratio, not the per-side sums.
 */
@Component
@Profile("live")
@ConditionalOnProperty(name = "artha.marketdata.source.optionanalytics", havingValue = "upstox")
public class UpstoxOptionAnalyticsSource {

  private static final Logger log = LoggerFactory.getLogger(UpstoxOptionAnalyticsSource.class);
  /** A point value over hourly buckets is enough — we read the day's final {@code data.*}. */
  private static final int BUCKET_MINUTES = 60;

  /** Our underlying index symbol → the Upstox Market-Information {@code instrument_key} (verified). */
  private static final Map<String, String> INSTRUMENT_KEYS =
      Map.of(
          "NIFTY 50", "NSE_INDEX|Nifty 50",
          "NIFTY BANK", "NSE_INDEX|Nifty Bank",
          "NIFTY FIN SERVICE", "NSE_INDEX|Nifty Fin Service",
          "NIFTY MID SELECT", "NSE_INDEX|NIFTY MID SELECT",
          "SENSEX", "BSE_INDEX|SENSEX",
          "BANKEX", "BSE_INDEX|BANKEX");

  private final UpstoxAnalyticsClient client;

  /**
   * @param client the Upstox Market-Information wire client
   */
  public UpstoxOptionAnalyticsSource(UpstoxAnalyticsClient client) {
    this.client = client;
  }

  /** PCR (4 dp) + max-pain strike (2 dp) from Upstox, matching the native scales. */
  public record Stats(BigDecimal pcr, BigDecimal maxPain) {}

  /**
   * Upstox PCR + max-pain for {@code (underlying, expiry, date)} — {@code null} on any miss (unknown
   * index, no Upstox data for the expiry/date, or transport/HTTP error) so the caller keeps the
   * native values. {@code date} null ⇒ today (IST), the live reading.
   */
  public Stats statsOrNull(String underlying, LocalDate expiry, LocalDate date) {
    String key = INSTRUMENT_KEYS.get(underlying);
    if (key == null) {
      log.warn("no Upstox instrument_key for underlying '{}' — keeping native PCR/max-pain", underlying);
      return null;
    }
    LocalDate d = date != null ? date : LocalDate.now(Ist.ZONE);
    // Two independent calls — a failure of one leg still overrides the other (max-pain and pcr are
    // separate Upstox endpoints), and ANY miss leaves that field native.
    BigDecimal maxPain = null;
    try {
      UpstoxOptionAnalytics.Data mp = client.maxPain(key, expiry, d, BUCKET_MINUTES);
      if (mp != null && mp.maxPain() != null) {
        maxPain = mp.maxPain().setScale(2, RoundingMode.HALF_UP);
      }
    } catch (RuntimeException failed) {
      log.warn("Upstox max-pain failed for {} ({}) — keeping native", underlying, failed.getMessage());
    }
    BigDecimal pcr = null;
    try {
      UpstoxOptionAnalytics.Data pc = client.pcr(key, expiry, d, BUCKET_MINUTES);
      if (pc != null && pc.pcr() != null) {
        pcr = pc.pcr().setScale(4, RoundingMode.HALF_UP);
      }
    } catch (RuntimeException failed) {
      log.warn("Upstox pcr failed for {} ({}) — keeping native", underlying, failed.getMessage());
    }
    if (maxPain == null && pcr == null) {
      log.warn("Upstox option-analytics empty for {} {} {} — keeping native", underlying, expiry, d);
      return null;
    }
    return new Stats(pcr, maxPain);
  }

  /**
   * Intraday PCR-vs-price series from Upstox {@code /pcr} insights ({@code bucketMinutes}
   * granularity) — each insight's {@code (time, pcr→4dp, spot_price)}. Empty list on any miss
   * (unknown index, no data, transport/HTTP error) so the caller falls back to the native fold.
   */
  public List<PcrSeriesPoint> pcrSeries(
      String underlying, LocalDate expiry, LocalDate date, int bucketMinutes) {
    String key = INSTRUMENT_KEYS.get(underlying);
    if (key == null) {
      log.warn("no Upstox instrument_key for underlying '{}' — keeping native PCR series", underlying);
      return List.of();
    }
    LocalDate d = date != null ? date : LocalDate.now(Ist.ZONE);
    try {
      UpstoxOptionAnalytics.Data pc = client.pcr(key, expiry, d, bucketMinutes);
      if (pc == null || pc.insights() == null) {
        return List.of();
      }
      List<PcrSeriesPoint> out = new ArrayList<>();
      for (UpstoxOptionAnalytics.Insight insight : pc.insights()) {
        if (insight.pcr() != null) {
          out.add(
              new PcrSeriesPoint(
                  insight.time(), insight.pcr().setScale(4, RoundingMode.HALF_UP), insight.spotPrice()));
        }
      }
      return out;
    } catch (RuntimeException failed) {
      log.warn("Upstox pcr-series failed for {} ({}) — keeping native", underlying, failed.getMessage());
      return List.of();
    }
  }
}

