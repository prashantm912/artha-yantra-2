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

  private static SignalRejectionRepository.RejectionRow contextlessRow() {
    try {
      return new SignalRejectionRepository.RejectionRow(
          1, null, "slug", "NFO", "FUT", "3m", "CE", "time-window", null, null, null, "r",
          null, null, MAPPER.readTree("{\"checks\":[{\"rail\":\"time-window\",\"pass\":false}]}"),
          OffsetDateTime.now(), OffsetDateTime.now());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void contextlessTailIsUninformativeNotAllDead() {
    // T17: after ~14:45 the newest rows are all early-rail blocks with NO context — 4 sessions of
    // false all-dead readings (breadth called dead while it supported 426/1,100). An all-context-
    // less window must read UNINFORMATIVE and never page.
    stubRows(contextlessRow(), contextlessRow(), contextlessRow());
    DotHealthCanary canary = canary("breadth");

    DotHealthCanary.DotHealth health = canary.evaluate();
    assertThat(health.rowsScanned()).isEqualTo(3);
    assertThat(health.rowsInspected()).as("no context-bearing rows").isZero();
    assertThat(health.dots()).allSatisfy(s -> assertThat(s.detail()).contains("UNINFORMATIVE"));

    canary.sweep();
    verifyNoInteractions(events);
  }

  @Test
  void contextBearingRowsAreProbedEvenBehindAContextlessTail() {
    // the newest rows are context-less but a live context-bearing row sits behind them — the
    // probes must reach past the tail instead of sampling only the newest N of any shape.
    stubRows(
        contextlessRow(), contextlessRow(),
        row("{\"macro\":{\"advances\":32,\"declines\":18}}"));
    DotHealthCanary.DotHealth health = canary("breadth").evaluate();

    assertThat(health.rowsInspected()).isEqualTo(1);
    assertThat(health.dots())
        .filteredOn(s -> s.dot().equals("breadth"))
        .allSatisfy(s -> assertThat(s.alive()).isTrue());
  }

  @Test
  void allNeutralQuadrantsReadDeadAndPage() {
    // T13: NEUTRAL is the data-missing sentinel (OiInterpretation.classify is total over 4 real
    // states) — the 2026-07-20 outage was 748/748 NEUTRAL with every canary green. An all-NEUTRAL
    // window must read the OI dots dead and page when they are required.
    stubRows(
        row("{\"oi\":{\"futuresQuadrant\":\"NEUTRAL\",\"underlyingQuadrant\":\"NEUTRAL\"},"
            + "\"macro\":{\"advances\":30,\"declines\":20}}"));
    DotHealthCanary canary = canary("futures_oi,underlying_oi");

    DotHealthCanary.DotHealth health = canary.evaluate();
    assertThat(health.dots())
        .filteredOn(s -> s.dot().equals("futures_oi") || s.dot().equals("underlying_oi"))
        .allSatisfy(s -> assertThat(s.alive()).isFalse());

    canary.sweep();
    verify(events, times(1)).publishEvent(alertContaining("futures_oi DEAD"));
    verify(events, times(1)).publishEvent(alertContaining("underlying_oi DEAD"));
  }

  @Test
  void expiryDayNeutralQuadrantsAreSuppressedNotDeadRequired() {
    // codex round 2/3: MarketOiClient degrades the OI reads to NEUTRAL on a monthly index-expiry
    // day BY DESIGN (S24) — the quadrant dots are then not "expected alive today": required must
    // drop so paging, /status and the UI badge all agree it is not an outage. Date discovered from
    // the bundled calendar so the test tracks the real CSV set.
    now.set(expiryDayAt11Ist());
    stubRows(
        row("{\"oi\":{\"futuresQuadrant\":\"NEUTRAL\",\"underlyingQuadrant\":\"NEUTRAL\"},"
            + "\"macro\":{\"advances\":30,\"declines\":20}}"));
    DotHealthCanary canary = canary("futures_oi,underlying_oi");

    DotHealthCanary.DotHealth health = canary.evaluate();
    assertThat(health.dots())
        .filteredOn(s -> s.dot().equals("futures_oi") || s.dot().equals("underlying_oi"))
        .allSatisfy(
            s -> {
              assertThat(s.required()).as("not expected alive on the suppression day").isFalse();
              assertThat(s.detail()).contains("by design");
            });

    canary.sweep();
    verifyNoInteractions(events);
  }

  @Test
  void expiryDaySuppressionCoversTheSpurtProbeNotJustTheQuadrants() {
    // 2026-07-28 live check: MarketOiClient.oi() SKIPS the whole OI block on a monthly index-expiry
    // day and returns an inert Oi (MarketOiClient:292-294) that NEUTRALs both quadrants AND nulls
    // the spurt magnitudes. The exemption set held only the two quadrant dots, so `oi_spurt_price`
    // reported "input dead across 16 context-bearing rejections" — a false outage — while its two
    // siblings correctly reported by-design. All three come off the SAME skipped read.
    now.set(expiryDayAt11Ist());
    stubRows(
        row("{\"oi\":{\"futuresQuadrant\":\"NEUTRAL\",\"underlyingQuadrant\":\"NEUTRAL\","
            + "\"spurtPricePct\":null},\"macro\":{\"advances\":30,\"declines\":20}}"));
    DotHealthCanary canary = canary("futures_oi,underlying_oi,oi_spurt_price");

    DotHealthCanary.DotHealth health = canary.evaluate();
    assertThat(health.dots())
        .filteredOn(s -> s.dot().equals("oi_spurt_price"))
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.alive()).isFalse();
              assertThat(s.required()).as("not expected alive on the suppression day").isFalse();
              assertThat(s.detail()).contains("by design").doesNotContain("input dead");
            });

    canary.sweep();
    verifyNoInteractions(events); // the whole OI trio is by-design today — nothing pages
  }

  @Test
  void aDeadSpurtProbeStillPagesOnAnOrdinaryDay() {
    // The other half of the exemption: widening it must not blind the probe on a NON-expiry day,
    // when a null spurt magnitude is a real outage.
    java.time.LocalDate ordinary = java.time.LocalDate.of(2026, 6, 10); // Wednesday, no monthly
    assertThat(in.arthayantra.marketcalendar.MarketCalendar.nse().isMonthlyIndexExpiryDay(ordinary))
        .as("fixture day must not be an NSE monthly expiry")
        .isFalse();
    assertThat(in.arthayantra.marketcalendar.MarketCalendar.bse().isMonthlyIndexExpiryDay(ordinary))
        .as("fixture day must not be a BSE monthly expiry")
        .isFalse();
    now.set(ordinary.atTime(11, 0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant());
    stubRows(
        row("{\"oi\":{\"spurtPricePct\":null},\"macro\":{\"advances\":30,\"declines\":20}}"));
    DotHealthCanary canary = canary("oi_spurt_price");

    DotHealthCanary.DotHealth health = canary.evaluate();
    assertThat(health.dots())
        .filteredOn(s -> s.dot().equals("oi_spurt_price"))
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.required()).isTrue();
              assertThat(s.detail()).contains("input dead");
            });

    canary.sweep();
    verify(events, times(1)).publishEvent(alertContaining("oi_spurt_price DEAD"));
  }

  @Test
  void oneCalendarExpiringDoesNotSuppressTheOtherRootsRows() {
    // codex review: MarketOiClient.oi() keys suppression on the ROW'S OWN underlying
    // (ScalperCalendars.forUnderlying — BSE Thursday monthly for SENSEX, NSE monthly otherwise), so
    // on an NSE-only expiry day a SENSEX-rooted OI read is NOT suppressed and a dead OI dot there is
    // a genuine outage. Treating either calendar's expiry as a blanket exemption silences it.
    java.time.LocalDate nseOnly = nseOnlyMonthlyExpiry2026();
    now.set(nseOnly.atTime(11, 0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant());
    String deadOi = "\"oi\":{\"futuresQuadrant\":\"NEUTRAL\",\"underlyingQuadrant\":\"NEUTRAL\","
        + "\"spurtPricePct\":null},\"macro\":{\"advances\":30,\"declines\":20}";

    // (a) window mixes a suppressed NIFTY root with a LIVE SENSEX root — must NOT stand down
    stubRows(
        row("{" + deadOi + ",\"underlying\":\"NIFTY 50\"}"),
        row("{" + deadOi + ",\"underlying\":\"SENSEX\"}"));
    DotHealthCanary mixed = canary("oi_spurt_price");
    assertThat(mixed.evaluate().dots())
        .filteredOn(s -> s.dot().equals("oi_spurt_price"))
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.required()).as("SENSEX root is not expiring — still expected").isTrue();
              assertThat(s.detail()).contains("input dead");
            });
    mixed.sweep();
    verify(events, times(1)).publishEvent(alertContaining("oi_spurt_price DEAD"));

    // (b) same DAY, all rows on the expiring NIFTY root — proves (a) is not passing merely because
    // the fixture date is wrong.
    stubRows(row("{" + deadOi + ",\"underlying\":\"NIFTY 50\"}"));
    assertThat(canary("oi_spurt_price").evaluate().dots())
        .filteredOn(s -> s.dot().equals("oi_spurt_price"))
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.required()).isFalse();
              assertThat(s.detail()).contains("by design");
            });
  }

  /** A 2026 day that is an NSE monthly index expiry but NOT a BSE one (NSE Tuesday vs BSE Thursday). */
  private static java.time.LocalDate nseOnlyMonthlyExpiry2026() {
    java.time.LocalDate d = java.time.LocalDate.of(2026, 1, 1);
    while (!(in.arthayantra.marketcalendar.MarketCalendar.nse().isMonthlyIndexExpiryDay(d)
        && !in.arthayantra.marketcalendar.MarketCalendar.bse().isMonthlyIndexExpiryDay(d))) {
      d = d.plusDays(1);
      if (d.getYear() > 2026) {
        throw new IllegalStateException("no NSE-only monthly expiry in 2026 — calendar changed");
      }
    }
    return d;
  }

  /** The bundled calendar's first NSE monthly index-expiry day of 2026, at 11:00 IST. */
  private static Instant expiryDayAt11Ist() {
    java.time.LocalDate expiry = java.time.LocalDate.of(2026, 1, 1);
    while (!in.arthayantra.marketcalendar.MarketCalendar.nse().isMonthlyIndexExpiryDay(expiry)) {
      expiry = expiry.plusDays(1);
    }
    return expiry.atTime(11, 0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
  }

  @Test
  void aLiveQuadrantReadsAlive() {
    stubRows(
        row("{\"oi\":{\"futuresQuadrant\":\"LONG_BUILDUP\",\"underlyingQuadrant\":\"SHORT_COVERING\"}}"));
    DotHealthCanary.DotHealth health = canary("futures_oi").evaluate();

    assertThat(health.dots())
        .filteredOn(s -> s.dot().equals("futures_oi") || s.dot().equals("underlying_oi"))
        .allSatisfy(s -> assertThat(s.alive()).isTrue());
  }
}
