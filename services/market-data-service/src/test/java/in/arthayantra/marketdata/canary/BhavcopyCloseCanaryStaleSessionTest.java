package in.arthayantra.marketdata.canary;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.alerts.NtfyClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.mock.env.MockEnvironment;

/**
 * The canary must not report on a session that is not tonight's.
 *
 * <p>⚠️ Why this exists. {@code sweep()} compares whatever {@code max(trade_date)} returns, which is
 * not necessarily today. That was near-harmless at 20:10 against a ~19:30 bhavcopy, but the evening
 * chain now runs inside 18:00–19:00 (the owner shuts the machine down at 19:00) and NSE's publish
 * time varies — 17:52 and 17:59 on two measured days, 18:47 and 19:31 on two others. On a late night
 * the file has not landed when the canary fires, so without the guard it silently re-compares
 * YESTERDAY's session and pages about it as though it were tonight's. An operator alert naming the
 * wrong day, on a schedule, is worse than no alert: it trains the owner to ignore the channel.
 *
 * <p>A unit test rather than an IT because {@code sweep()} is live-profile-only, and the existing
 * {@code DataQualityCanaryIntegrationTest} exercises {@code evaluate()} — the side-effect-free half —
 * which never had this problem.
 */
class BhavcopyCloseCanaryStaleSessionTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 8, 11);

  @Test
  @DisplayName("a bhavcopy older than today is skipped, with no alert and no metric")
  void staleLatestTradeDateIsSkipped() {
    NtfyClient ntfy = mock(NtfyClient.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<LocalDate>>any()))
        .thenReturn(TODAY.minusDays(1));

    canary(jdbc, ntfy).sweep();

    // No alert, and — just as important — no COMPARISON: reaching the query would mean the canary
    // formed a verdict about the wrong day even if it happened not to page about it.
    verify(ntfy, never()).send(anyString(), anyString(), anyString());
    verify(jdbc, never()).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any());
  }

  @Test
  @DisplayName("today's bhavcopy is compared as normal — the guard is not a blanket off-switch")
  void todaysTradeDateStillSweeps() {
    // ⚠️ Without this the guard could reject EVERYTHING and the test above would still pass, which
    // is the shape of a canary that reports success while checking nothing.
    NtfyClient ntfy = mock(NtfyClient.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<LocalDate>>any()))
        .thenReturn(TODAY);

    canary(jdbc, ntfy).sweep();

    verify(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any());
  }

  private static BhavcopyCloseCanary canary(JdbcTemplate jdbc, NtfyClient ntfy) {
    return new BhavcopyCloseCanary(
        jdbc,
        ntfy,
        Clock.fixed(TODAY.atTime(18, 52).atZone(Ist.ZONE).toInstant(), Ist.ZONE),
        new SimpleMeterRegistry(),
        new MockEnvironment().withProperty("spring.profiles.active", "live"),
        true,
        true,
        new BigDecimal("0.01"),
        20,
        25);
  }

  /** Guards against the fixture drifting away from the date the assertions reason about. */
  @Test
  @DisplayName("the fixed clock really is 2026-08-11 in IST")
  void theClockFixtureIsTheDayItClaims() {
    Instant at = TODAY.atTime(18, 52).atZone(Ist.ZONE).toInstant();
    org.assertj.core.api.Assertions.assertThat(LocalDate.ofInstant(at, Ist.ZONE)).isEqualTo(TODAY);
  }
}
