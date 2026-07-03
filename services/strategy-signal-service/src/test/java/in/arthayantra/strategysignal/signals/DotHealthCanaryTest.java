package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * F4 v2: the gate-input liveness watcher — a REQUIRED dot with no live input across today's
 * rejections alerts once per day; the endpoint reports every dot; engine silence stays the
 * data-plane canary's problem.
 */
class DotHealthCanaryTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  // 2026-06-10 is a Wednesday trading day; 11:00 IST = 05:30Z
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-06-10T05:30:00Z"));
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

  private final SignalRejectionRepository rejections = mock(SignalRejectionRepository.class);
  private final org.springframework.context.ApplicationEventPublisher events =
      mock(org.springframework.context.ApplicationEventPublisher.class);

  private DotHealthCanary canary(String required) {
    return new DotHealthCanary(rejections, events, clock, required);
  }

  private DotHealthCanary.DotInputAlert alertContaining(String titlePart) {
    return org.mockito.ArgumentMatchers.argThat(
        a -> a instanceof DotHealthCanary.DotInputAlert alert && alert.title().contains(titlePart));
  }

  private static SignalRejectionRepository.RejectionRow row(String contextJson) {
    try {
      return new SignalRejectionRepository.RejectionRow(
          1, null, "slug", "NFO", "FUT", "3m", "CE", "volume-floor", null, null, null, "r",
          null, null, MAPPER.readTree("{\"context\":" + contextJson + "}"),
          OffsetDateTime.now(), OffsetDateTime.now());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void stubRows(SignalRejectionRepository.RejectionRow... rows) {
    when(rejections.list(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(rows));
  }

  @Test
  void deadRequiredDotAlertsOncePerDayAndRecoveryNotesOnce() {
    // breadth input dead (0/0) across the window
    stubRows(
        row("{\"macro\":{\"advances\":0,\"declines\":0,\"ivRank\":null}}"),
        row("{\"macro\":{\"advances\":0,\"declines\":0}}"));
    DotHealthCanary canary = canary("breadth");

    canary.sweep();
    canary.sweep(); // same day — no second push
    verify(events, times(1)).publishEvent(alertContaining("breadth DEAD"));

    // breadth comes alive → one recovery note
    stubRows(row("{\"macro\":{\"advances\":32,\"declines\":18}}"));
    canary.sweep();
    verify(events, times(1)).publishEvent(alertContaining("recovered"));
  }

  @Test
  void endpointReportsEveryDotWithRequiredFlag() {
    stubRows(
        row("{\"macro\":{\"advances\":30,\"declines\":20,\"ivRank\":null,\"dowUp\":null,"
            + "\"fiiLongPct\":55.0,\"vixLevel\":12.4},\"oi\":{\"spurtPricePct\":0}}"));
    DotHealthCanary.DotHealth health = canary("breadth,iv_rank").evaluate();

    assertThat(health.rowsInspected()).isEqualTo(1);
    assertThat(health.dots())
        .extracting(DotHealthCanary.DotState::dot, DotHealthCanary.DotState::alive,
            DotHealthCanary.DotState::required)
        .contains(
            org.assertj.core.groups.Tuple.tuple("breadth", true, true),
            org.assertj.core.groups.Tuple.tuple("iv_rank", false, true),
            org.assertj.core.groups.Tuple.tuple("fii", true, false),
            org.assertj.core.groups.Tuple.tuple("vix", true, false),
            org.assertj.core.groups.Tuple.tuple("dow", false, false),
            org.assertj.core.groups.Tuple.tuple("oi_spurt_price", false, false));
  }

  @Test
  void engineSilenceAndOffSessionStaySilent() {
    stubRows(); // zero rejections today
    DotHealthCanary canary = canary("breadth");
    canary.sweep();
    verifyNoInteractions(events); // engine silence = data-plane canary's job

    // Saturday — off-session, no evaluation-driven alerts either
    now.set(Instant.parse("2026-06-13T05:30:00Z"));
    stubRows(row("{\"macro\":{\"advances\":0,\"declines\":0}}"));
    canary.sweep();
    verifyNoInteractions(events);
  }

  @Test
  void nonRequiredDeadDotNeverPages() {
    stubRows(row("{\"macro\":{\"advances\":30,\"declines\":20,\"ivRank\":null,\"dowUp\":null}}"));
    DotHealthCanary canary = canary("breadth");
    canary.sweep();
    verifyNoInteractions(events); // iv_rank/dow dead but not required
  }
}
