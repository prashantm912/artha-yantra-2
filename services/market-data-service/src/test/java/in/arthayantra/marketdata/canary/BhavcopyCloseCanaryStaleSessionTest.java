package in.arthayantra.marketdata.canary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.alerts.NtfyClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * not necessarily today. NSE's publish time varies — measured 17:52, 17:59, 18:47 and 19:31 across
 * four days — so the 20:10 sweep already has a thin margin against a 19:30 bhavcopy, and the pending
 * move of the evening chain into 18:00–19:00 (the owner shuts the machine down at 19:00) removes it
 * entirely. On a late night the file has not landed when the canary fires, so without the guard it silently re-compares
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
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<LocalDate>>any()))
        .thenReturn(TODAY.minusDays(1));

    canary(jdbc, ntfy, meters).sweep();

    // No alert, and — just as important — no COMPARISON: reaching the query would mean the canary
    // formed a verdict about the wrong day even if it happened not to page about it.
    verify(ntfy, never()).send(anyString(), anyString(), anyString());
    verify(jdbc, never()).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any());
    // The DisplayName claims "no metric" — assert it rather than assert it in prose (reviewer's
    // Suggestion). A skipped sweep that still moved the divergence counter would corrupt the very
    // number an operator uses to judge whether the feeds agree.
    assertThat(meters.counter("ay_bhavcopy_close_divergence_total").count())
        .as("a skipped sweep must not move the divergence counter")
        .isZero();
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

  @Test
  @DisplayName("the completion listener runs the comparison the late-publish night would have lost")
  void theCompletionListenerRecoversALatePublish() {
    // The cron is 18:52 and the bhavcopy submit at 18:45 is ASYNCHRONOUS, so on a 19:31 publish the
    // cron finds nothing and the guard skips — permanently, because by its next firing that session
    // is no longer today. This is the entry point that closes it.
    NtfyClient ntfy = mock(NtfyClient.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<LocalDate>>any()))
        .thenReturn(TODAY);

    canary(jdbc, ntfy).onBhavcopyCompleted();

    verify(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any());
  }

  @Test
  @DisplayName("event THEN cron does not compare the same session twice — the common production order")
  void theListenerThenTheCronDoNotBothAlert() {
    // ⚠️ The first version of this suite only tested cron-then-event, and `lastSwept` was only read
    // by the listener — so it suppressed the order that is RARE and did nothing about the order that
    // is common. NSE published before 18:52 on three of four measured days, meaning the event
    // normally lands FIRST and the cron then re-compares the same session. Cross-vendor review
    // called the original proof vacuous for exactly this reason.
    NtfyClient ntfy = mock(NtfyClient.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<LocalDate>>any()))
        .thenReturn(TODAY);

    BhavcopyCloseCanary canary = canary(jdbc, ntfy);
    canary.onBhavcopyCompleted();
    canary.sweep();

    verify(jdbc, times(1))
        .query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any());
  }

  @Test
  @DisplayName("next-morning replay compares the LATE session the cron skipped, not nothing")
  void theListenerRecoversYesterdaysLatePublish() {
    // The 19:31 night: the file lands after the machine is off, so nothing compares it. Next morning
    // BhavcopyStartupCatchup replays the backfill, completion fires, and `latest` is YESTERDAY. The
    // cron's stale-date guard would skip that forever — the session is never today again. The
    // listener must NOT inherit that guard, because it only runs when a fetch actually completed.
    NtfyClient ntfy = mock(NtfyClient.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<LocalDate>>any()))
        .thenReturn(TODAY.minusDays(1));

    canary(jdbc, ntfy).onBhavcopyCompleted();

    verify(jdbc)
        .query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any());
  }

  @Test
  @DisplayName("a listener failure never escapes into the completion multicast")
  void theListenerCannotBreakTheChainItObserves() {
    // Spring multicasts BhavcopyBackfillCompleted SYNCHRONOUSLY and the publisher catches only
    // around the whole multicast, so an exception escaping this observer can stop Minervini and
    // Manas from ever receiving completion. A canary must not be able to break the chain it watches.
    NtfyClient ntfy = mock(NtfyClient.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<LocalDate>>any()))
        .thenThrow(new IllegalStateException("connection pool exhausted"));

    assertThatCode(() -> canary(jdbc, ntfy).onBhavcopyCompleted())
        .as("the observer must swallow its own failure — the screens depend on this event")
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a completion that landed nothing re-compares nothing — the claim, not the date, dedupes")
  void aCompletionThatLandedNothingDoesNotReCompare() {
    // ⚠️ This test used to assert the OPPOSITE — that the listener inherits the cron's stale-date
    // guard — and that assertion is precisely what left the Critical open: it froze "skip anything
    // before today" as correct on the one path that can legitimately compare an earlier session.
    //
    // Completion is published even when both exchanges returned nothing, so the event still does not
    // prove a file arrived. What handles that is the CLAIM, not the date: an empty completion leaves
    // `latest` where it was, and a session already compared is not compared again. Here the same
    // session arrives twice; only the first is evaluated.
    NtfyClient ntfy = mock(NtfyClient.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<LocalDate>>any()))
        .thenReturn(TODAY);

    BhavcopyCloseCanary canary = canary(jdbc, ntfy);
    canary.onBhavcopyCompleted();
    canary.onBhavcopyCompleted();

    verify(jdbc, times(1))
        .query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any());
  }

  @Test
  @DisplayName("cron then event does not compare the same session twice")
  void theCronAndTheListenerDoNotBothAlert() {
    // The common case is the file landing BEFORE 18:52, so both entry points fire on the same
    // session. Without lastSwept that is two comparisons and two possible pages for one night.
    NtfyClient ntfy = mock(NtfyClient.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<LocalDate>>any()))
        .thenReturn(TODAY);

    BhavcopyCloseCanary canary = canary(jdbc, ntfy);
    canary.sweep();
    canary.onBhavcopyCompleted();

    verify(jdbc, times(1))
        .query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any());
  }

  /**
   * ⚠️ The clock is built in UTC, at an instant whose UTC date and IST date DIFFER:
   * {@code 2026-08-10T19:00:00Z} is 2026-08-11 00:30 IST. So "today" is 08-10 to a JVM-zone reading
   * and 08-11 to an IST one, and every assertion here depends on getting 08-11 — the fixture fails
   * if the production code drops the zone.
   *
   * <p>Reviewer's Suggestion, and it was right: the previous fixture built the instant FROM
   * {@code Ist.ZONE} and a separate test "proved" it by converting a date to an instant and back
   * through that same zone — true by construction, and it never touched the {@code Clock} the canary
   * was actually given. That test is gone; this fixture carries the property instead.
   */
  private static BhavcopyCloseCanary canary(JdbcTemplate jdbc, NtfyClient ntfy, MeterRegistry meters) {
    return new BhavcopyCloseCanary(
        jdbc,
        ntfy,
        Clock.fixed(Instant.parse("2026-08-10T19:00:00Z"), ZoneOffset.UTC),
        meters,
        new MockEnvironment().withProperty("spring.profiles.active", "live"),
        true,
        true,
        new BigDecimal("0.01"),
        20,
        25);
  }

  private static BhavcopyCloseCanary canary(JdbcTemplate jdbc, NtfyClient ntfy) {
    return canary(jdbc, ntfy, new SimpleMeterRegistry());
  }
}
