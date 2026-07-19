package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.feeds.FiiDerivativeFetcher;
import in.arthayantra.marketdata.feeds.FiiDiiFetcher;
import in.arthayantra.marketdata.feeds.NewsSource;
import in.arthayantra.marketdata.nse.LiveFiiDiiFetcher;
import in.arthayantra.marketdata.upstox.canary.UpstoxContractCanary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

/**
 * Live-profile Upstox Market-Information wiring (ADR-0002). The analytics client binds only when
 * {@code artha.upstox.analytics.enabled=true} — dormant by default (same default-off discipline as
 * the OpenAlgo data-foundation flags), so a Kite/OpenAlgo-only live stack needs no Upstox token. The
 * dedicated analytics token is read from its secret file inside {@link UpstoxAnalyticsProperties}.
 */
@Configuration
@Profile("live")
@EnableConfigurationProperties(UpstoxAnalyticsProperties.class)
public class UpstoxAnalyticsConfig {

  /**
   * The ONE token-scoped Upstox rate budget (EXT-02) — a single bean shared by EVERY client on the
   * analytics token (the expired-backfill walker + the fundamentals bulk walk + the daily contract
   * canary + the live option-chain / quote / margin / ws-authorize / page clients), so the whole token
   * draws from the ONE 2000/30min budget instead of each client fragmenting it (or running unmetered).
   * The batch path pauses through the batch-quiet guard window — from one full 30-min rate-window
   * BEFORE the session open through the close — so the 30-min deque has drained of batch hits by the
   * open and the live capture path owns the WHOLE budget during the session. An uncovered calendar year
   * is treated as NOT quiet (batch runs), which is safe because live capture is also halted then. Bound
   * whenever ANY analytics-token client can bind — analytics enabled, OR the quote / chain / ticker
   * source flipped to Upstox, OR the canary enabled — so the injected dependency always exists; absent
   * on the mock stack (non-live profile) so the B4 quota widget reports {@code configured=false}.
   */
  @Bean
  @ConditionalOnExpression(
      "'${artha.upstox.analytics.enabled:false}' == 'true' "
          + "or '${artha.marketdata.source.optionchain:kite}' == 'upstox' "
          + "or '${artha.marketdata.source.quotes:kite}' == 'upstox' "
          + "or '${artha.marketdata.source.ticker:kite}' == 'upstox' "
          + "or '${artha.upstox.canary-enabled:false}' == 'true'")
  public UpstoxRateLimiter upstoxRateLimiter(MarketCalendar calendar, Clock clock) {
    return new UpstoxRateLimiter(() -> isBatchQuiet(calendar, clock));
  }

  /** One full 30-min rate-window of lead time, so the deque fully drains of batch hits before the open. */
  private static final Duration BATCH_QUIET_LEAD = Duration.ofMinutes(30);

  /**
   * The batch-quiet guard window: true from {@link #BATCH_QUIET_LEAD} before the session open (derived
   * from the calendar, not hardcoded) through the close, on a trading day — so batch work stops before
   * open and the 30-min deque has drained of its hits by 09:15. Tolerant of an uncovered calendar year
   * (→ not quiet, batch runs; safe because live capture is halted then).
   */
  private static boolean isBatchQuiet(MarketCalendar calendar, Clock clock) {
    Instant now = clock.instant();
    LocalDate day = now.atZone(Ist.ZONE).toLocalDate();
    try {
      return calendar
          .sessionBounds(day)
          .map(b -> !now.isBefore(b.open().minus(BATCH_QUIET_LEAD)) && now.isBefore(b.close()))
          .orElse(false);
    } catch (IllegalArgumentException uncoveredYear) {
      return false;
    }
  }

  /** The hand-rolled Upstox analytics REST client (entitlement probe in U1; feeds U2+). */
  @Bean
  @ConditionalOnProperty(name = "artha.upstox.analytics.enabled", havingValue = "true")
  public UpstoxAnalyticsClient upstoxAnalyticsClient(
      RestClient.Builder restClientBuilder,
      UpstoxAnalyticsProperties properties,
      UpstoxRateLimiter upstoxRateLimiter) {
    return new UpstoxAnalyticsClient(restClientBuilder, properties, upstoxRateLimiter);
  }

  /**
   * Upstox per-stock news source — the {@link NewsSource} adapter over {@code GET /v2/news}. Bound
   * whenever the analytics token is enabled (same gating as the client it wraps); {@code
   * EquityNewsService} consumes it via an {@code ObjectProvider}, so its absence is what makes the
   * news page report {@code available=false}.
   */
  @Bean
  @ConditionalOnProperty(name = "artha.upstox.analytics.enabled", havingValue = "true")
  public NewsSource upstoxNewsSource(UpstoxAnalyticsClient client) {
    return new UpstoxNewsSource(client);
  }

  /**
   * The Upstox <b>expired-instruments</b> REST client — feeds {@code ExpiredBackfillService} (backtest
   * data). Same analytics token + base URL as the Market-Information client; bound only when the
   * analytics token is enabled (else the admin {@code /expired-backfill} trigger 503s as unconfigured).
   */
  @Bean
  @ConditionalOnProperty(name = "artha.upstox.analytics.enabled", havingValue = "true")
  public UpstoxExpiredInstrumentsClient upstoxExpiredInstrumentsClient(
      RestClient.Builder restClientBuilder,
      UpstoxAnalyticsProperties properties,
      UpstoxRateLimiter upstoxRateLimiter) {
    return new UpstoxExpiredInstrumentsClient(restClientBuilder, properties, upstoxRateLimiter);
  }


  /**
   * Upstox <b>NSE_EQ instrument-master</b> client — feeds the Phase-5 200-day equity daily backfill
   * ({@code EquityDailyBackfillService}). Resolves a cash-equity tradingsymbol → its {@code
   * NSE_EQ|<ISIN>} key off the token-free assets-CDN master. Bound only when the analytics token is
   * enabled (its sibling {@link UpstoxExpiredInstrumentsClient} that fetches the daily candles needs
   * the token); absent ⇒ the backfill trigger 503s as unconfigured.
   */
  @Bean
  @ConditionalOnProperty(name = "artha.upstox.analytics.enabled", havingValue = "true")
  public UpstoxEquityMasterClient upstoxEquityMasterClient(
      RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper,
      UpstoxAnalyticsProperties properties) {
    return new UpstoxEquityMasterClient(restClientBuilder, objectMapper, properties);
  }

  /**
   * Upstox <b>Company Fundamentals</b> client (ADR-0004) — the ISIN-keyed financial endpoints
   * (share-holdings / key-ratios / income-statement) that source the Minervini low-cap universe gate
   * (free-float market cap + free-float %) and the §4.8 ROE read. Bound only when the analytics token
   * is enabled; {@code FundamentalsService} consumes it via an {@code ObjectProvider}, so its absence
   * makes a fundamentals refresh report everything skipped (never a 5xx).
   */
  @Bean
  @ConditionalOnProperty(name = "artha.upstox.analytics.enabled", havingValue = "true")
  public UpstoxFundamentalsClient upstoxFundamentalsClient(
      RestClient.Builder restClientBuilder,
      UpstoxAnalyticsProperties properties,
      UpstoxRateLimiter upstoxRateLimiter) {
    return new UpstoxFundamentalsClient(restClientBuilder, properties, upstoxRateLimiter);
  }

  /**
   * Upstox <b>global-instrument</b> client — feeds the World Indices page ({@code GET
   * /api/v1/market/world-indices}). It downloads the public global instrument master from the assets
   * CDN ({@code assets.upstox.com}, no token) and live-quotes the {@code GLOBAL_INDEX} /
   * {@code GLOBAL_INDICATOR} roster via {@code /v2/market-quote/quotes} on the analytics token. Bound
   * only when the analytics token is enabled; absent ⇒ the page renders empty (the controller resolves
   * the bean via an {@code ObjectProvider}).
   */
  @Bean
  @ConditionalOnProperty(name = "artha.upstox.analytics.enabled", havingValue = "true")
  public UpstoxGlobalInstrumentsClient upstoxGlobalInstrumentsClient(
      RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper,
      UpstoxAnalyticsProperties properties,
      UpstoxRateLimiter upstoxRateLimiter) {
    return new UpstoxGlobalInstrumentsClient(
        restClientBuilder, objectMapper, properties, upstoxRateLimiter);
  }

  /**
   * Upstox <b>market-status + index pre-open</b> client — feeds the Pre-Open Market page ({@code GET
   * /api/v1/market/market-status} + {@code GET /api/v1/market/pre-open}). It reads the per-exchange session
   * phase ({@code /v2/market/status/{exchange}}) and batch-quotes the four index keys (Nifty 50 / Bank
   * / Fin / Sensex via {@code /v2/market-quote/quotes}) on the analytics token. Bound only when the
   * analytics token is enabled; absent ⇒ the page renders empty / UNKNOWN (the controller resolves the
   * bean via an {@code ObjectProvider}).
   */
  @Bean
  @ConditionalOnProperty(name = "artha.upstox.analytics.enabled", havingValue = "true")
  public UpstoxMarketStatusClient upstoxMarketStatusClient(
      RestClient.Builder restClientBuilder,
      UpstoxAnalyticsProperties properties,
      UpstoxRateLimiter upstoxRateLimiter) {
    return new UpstoxMarketStatusClient(restClientBuilder, properties, upstoxRateLimiter);
  }

  /**
   * Direct-Upstox LIVE option-chain client (Wave U1) — the flag-selected {@link
   * in.arthayantra.marketdata.options.OptionChainQuoteSource} for the live OI snapshot. Bound ONLY
   * when {@code artha.marketdata.source.optionchain=upstox}; absent ⇒ {@code OptionsChainService}
   * keeps sourcing per-strike LTP+OI from the Kite {@code QuoteGateway} (the unchanged default). On
   * the long-lived 1-yr analytics token, so OI capture survives a missed daily Kite login.
   */
  @Bean
  @ConditionalOnProperty(name = "artha.marketdata.source.optionchain", havingValue = "upstox")
  public UpstoxOptionChainClient upstoxOptionChainClient(
      RestClient.Builder restClientBuilder,
      UpstoxAnalyticsProperties properties,
      UpstoxRateLimiter upstoxRateLimiter) {
    return new UpstoxOptionChainClient(restClientBuilder, properties, upstoxRateLimiter);
  }

  /**
   * Upstox-primary FII/DII cash source (U2), bound as {@code @Primary} when {@code
   * artha.marketdata.source.fiidii=upstox} so the NSE scheduler persists Upstox flows; the NSE
   * {@code LiveFiiDiiFetcher} stays the swap-out fallback. REQUIRES {@code
   * artha.upstox.analytics.enabled=true} (it consumes {@link UpstoxAnalyticsClient}) — flip only
   * after the U1 entitlement probe is green.
   */
  @Bean
  @Primary
  @ConditionalOnProperty(name = "artha.marketdata.source.fiidii", havingValue = "upstox")
  public FiiDiiFetcher upstoxFiiDiiFetcher(
      UpstoxAnalyticsClient client, LiveFiiDiiFetcher nseFallback) {
    return new UpstoxFiiDiiFetcher(client, nseFallback);
  }

  /**
   * Upstox FII-derivative source (U6) — bound whenever the analytics token is enabled (there is no
   * NSE equivalent, so no source flag and no fallback). The {@code NseEodScheduler} consumes it via
   * an {@code ObjectProvider}, so its absence (analytics off) is a no-op.
   */
  @Bean
  @ConditionalOnProperty(name = "artha.upstox.analytics.enabled", havingValue = "true")
  public FiiDerivativeFetcher upstoxFiiDerivativeFetcher(UpstoxAnalyticsClient client) {
    return new UpstoxFiiDerivativeFetcher(client);
  }

  /**
   * Direct-Upstox pre-trade SPAN margin client (F9 risk-layer source). Bound whenever the analytics
   * token is enabled — {@code POST /v2/charges/margin} computes SPAN+exposure server-side on the
   * login-free analytics token, so the platform never needs an {@code .spn} file. {@code
   * MarginController} consumes it via an {@code ObjectProvider}, so absent (analytics off) ⇒ the
   * margin endpoint returns an {@code unpriced} response, never a 5xx.
   */
  @Bean
  @ConditionalOnProperty(name = "artha.upstox.analytics.enabled", havingValue = "true")
  public UpstoxMarginClient upstoxMarginClient(
      RestClient.Builder restClientBuilder,
      UpstoxAnalyticsProperties properties,
      UpstoxRateLimiter upstoxRateLimiter) {
    return new UpstoxMarginClient(restClientBuilder, properties, upstoxRateLimiter);
  }

  /**
   * Daily Upstox Market-Information contract canary (U7) — wired only when {@code
   * artha.upstox.canary-enabled=true}. Off by default so a stack without the analytics token needs
   * no Upstox probe. Self-scheduled (daily cron, IST); raw-JSON probes diffed vs the committed
   * manifest, ntfy on drift. Separate flag from {@code analytics.enabled} so the canary can be left
   * off (no ntfy noise) even while the analytics consumers run.
   */
  @Bean
  @ConditionalOnProperty(name = "artha.upstox.canary-enabled", havingValue = "true")
  public UpstoxContractCanary upstoxContractCanary(
      RestClient.Builder restClientBuilder,
      UpstoxAnalyticsProperties properties,
      StringRedisTemplate redis,
      NtfyClient ntfy,
      MarketCalendar calendar,
      Clock clock,
      ObjectMapper objectMapper,
      ObjectProvider<UpstoxFnoMasterClient> fnoMaster,
      MeterRegistry meterRegistry,
      UpstoxRateLimiter upstoxRateLimiter) {
    return new UpstoxContractCanary(
        restClientBuilder, properties, redis, ntfy, calendar, clock, objectMapper, fnoMaster,
        meterRegistry, upstoxRateLimiter);
  }
}
