package in.arthayantra.marketdata.canary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.feed.NormalizedTick;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * Roadmap F4: the canary must catch the 2026-07-03 signature (ticks flowing, bars dead) without
 * false-positives on boot, day rollover, new subscriptions, quiet tokens or a closed market.
 */
class DataHealthCanaryTest {

  // 2026-06-10 is a Wednesday trading day; 12:40 IST = 07:10Z
  private static final Instant MIDSESSION = Instant.parse("2026-06-10T07:10:00Z");

  private final AtomicReference<Instant> now = new AtomicReference<>(MIDSESSION);
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };

  private final NtfyClient ntfy = mock(NtfyClient.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final DataHealthState state = new DataHealthState(clock, "");

  private DataHealthCanary canary(boolean live) {
    MockEnvironment env = new MockEnvironment();
    if (live) {
      env.setActiveProfiles("live");
    }
    return new DataHealthCanary(
        state, ntfy, jdbc, MarketCalendar.nse(), clock, env, true, false, 90, 240, 600, 900, 30);
  }

  private void tick(String exchange, String symbol) {
    state.onNormalizedTick(
        new NormalizedTick(
            exchange, symbol, new BigDecimal("100.00"), 1000, null,
            OffsetDateTime.ofInstant(now.get(), ZoneOffset.ofHoursMinutes(5, 30)), now.get().toEpochMilli()));
  }

  /**
   * A token established well before "now", ticking fresh on a DENSE tape (~40 ticks since its last
   * bar — the real-stall shape the T20 density guard requires), with its last bar {@code barAge} ago.
   */
  private void establishToken(String symbol, java.time.Duration barAge) {
    Instant t = now.get();
    now.set(t.minusSeconds(1200));
    tick("NSE", symbol);
    if (barAge != null) {
      now.set(t.minus(barAge));
      state.recordBar("NSE", symbol);
    }
    for (int i = 40; i >= 1; i--) {
      now.set(t.minusSeconds(10L * i));
      tick("NSE", symbol);
    }
    now.set(t);
  }

  @Test
  void thinTapeFewTicksSinceLastCloseNeverFlags() {
    // the FINNIFTY26SEPFUT shape (T20, bug queue B6): last bar 15 minutes old but only a handful
    // of ticks since — the tape is thin, the pipeline is fine. Paged 5 sessions running before the
    // density guard; must stay silent now.
    Instant t = now.get();
    now.set(t.minusSeconds(1200));
    tick("NSE", "FINNIFTY26SEPFUT");
    now.set(t.minusSeconds(900));
    state.recordBar("NSE", "FINNIFTY26SEPFUT");
    now.set(t.minusSeconds(300));
    tick("NSE", "FINNIFTY26SEPFUT");
    now.set(t.minusSeconds(30));
    tick("NSE", "FINNIFTY26SEPFUT");
    now.set(t);

    CanaryReport report = canary(false).evaluate();
    assertThat(report.problems()).isEmpty();
    assertThat(report.status()).isEqualTo("GREEN");
  }

  @Test
  void divergenceIsRedAndAlerts() {
    // the 12:40 stall: ticks fresh, last bar 5 minutes old
    establishToken("NIFTY26JULFUT", java.time.Duration.ofMinutes(5));
    DataHealthCanary canary = canary(false);

    CanaryReport report = canary.evaluate();
    assertThat(report.status()).isEqualTo("RED");
    assertThat(report.marketOpen()).isTrue();
    assertThat(report.problems())
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.check()).isEqualTo("TICK_BAR_DIVERGENCE");
              assertThat(p.key()).isEqualTo("NSE:NIFTY26JULFUT");
              assertThat(p.status()).isEqualTo("RED");
            });

    canary.sweep();
    verify(ntfy).send(contains("TICK_BAR_DIVERGENCE"), eq("urgent"), contains("NSE:NIFTY26JULFUT"));
  }

  @Test
  void cooldownSuppressesRepeatAlertsAndRecoveryNotesOnce() {
    establishToken("NIFTY26JULFUT", java.time.Duration.ofMinutes(5));
    DataHealthCanary canary = canary(false);

    canary.sweep();
    now.set(now.get().plusSeconds(60));
    canary.sweep(); // within the 900 s cooldown — no second push
    verify(ntfy, times(1)).send(anyString(), eq("urgent"), anyString());

    // bar publishing resumes → one recovery note
    state.recordBar("NSE", "NIFTY26JULFUT");
    now.set(now.get().plusSeconds(60));
    tick("NSE", "NIFTY26JULFUT");
    canary.sweep();
    verify(ntfy).send(contains("recovered"), eq("default"), contains("NSE:NIFTY26JULFUT"));
  }

  @Test
  void newSubscriptionAndQuietTokenNeverFalseRed() {
    // a UI-added token whose FIRST tick just arrived: its first bar is still a minute away
    tick("NSE", "TCS");
    // a token that stopped ticking entirely (feed-level problem, the FeedWatchdog's job)
    Instant t = now.get();
    now.set(t.minusSeconds(1200));
    tick("NSE", "QUIET");
    now.set(t);

    assertThat(canary(false).evaluate().status()).isEqualTo("GREEN");
  }

  @Test
  void dayRolloverArmingWindowSilencesTheOpen() {
    // 09:16 IST: yesterday's last bar is ancient but the session is younger than bar-stale
    now.set(Instant.parse("2026-06-10T03:46:00Z"));
    establishToken("NIFTY26JULFUT", java.time.Duration.ofHours(18));
    assertThat(canary(false).evaluate().problems()).isEmpty();
  }

  @Test
  void closedMarketIsAlwaysGreenAndSilent() {
    now.set(Instant.parse("2026-06-13T07:10:00Z")); // Saturday
    establishToken("NIFTY26JULFUT", java.time.Duration.ofMinutes(30));
    DataHealthCanary canary = canary(false);
    CanaryReport report = canary.evaluate();
    assertThat(report.status()).isEqualTo("GREEN");
    assertThat(report.marketOpen()).isFalse();
    canary.sweep();
    verifyNoInteractions(ntfy);
  }

  @Test
  void feedWideStallCollapsesIntoOneSummaryAlert() {
    for (int i = 0; i < 6; i++) {
      establishToken("SYM" + i, java.time.Duration.ofMinutes(5));
    }
    DataHealthCanary canary = canary(false);
    canary.sweep();
    verify(ntfy, times(1)).send(anyString(), eq("urgent"), contains("6 instruments"));
  }

  @Test
  void captureFreshnessChecksRunOnlyOnLiveProfile() {
    when(jdbc.queryForObject(anyString(), eq(Timestamp.class), any(Timestamp.class)))
        .thenReturn(Timestamp.from(now.get().minusSeconds(1800)));

    CanaryReport mock = canary(false).evaluate();
    assertThat(mock.problems()).isEmpty();
    verifyNoInteractions(jdbc);

    CanaryReport live = canary(true).evaluate();
    assertThat(live.problems())
        .extracting(CheckResult::check)
        .containsExactlyInAnyOrder("OPTIONS_CAPTURE", "FUTURES_OI_CAPTURE");
    assertThat(live.status()).isEqualTo("RED");
  }

  @Test
  void captureProbeFailureDegradesToAmberNotRed() {
    when(jdbc.queryForObject(anyString(), eq(Timestamp.class), any(Timestamp.class)))
        .thenThrow(new RuntimeException("connection refused"));
    CanaryReport report = canary(true).evaluate();
    assertThat(report.status()).isEqualTo("AMBER");
    assertThat(report.problems()).allSatisfy(p -> assertThat(p.status()).isEqualTo("AMBER"));
    // AMBER never pushes — only RED alerts
    canary(true).sweep();
    verify(ntfy, never()).send(anyString(), eq("urgent"), anyString());
  }

  @Test
  void freshCaptureIsGreenOnLive() {
    when(jdbc.queryForObject(anyString(), eq(Timestamp.class), any(Timestamp.class)))
        .thenReturn(Timestamp.from(now.get().minusSeconds(120)));
    assertThat(canary(true).evaluate().status()).isEqualTo("GREEN");
  }
}
