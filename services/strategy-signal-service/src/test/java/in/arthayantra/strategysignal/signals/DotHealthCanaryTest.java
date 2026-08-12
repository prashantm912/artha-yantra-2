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
    return rowAtBar(contextJson, OffsetDateTime.now());
  }

  /** A context-bearing row pinned to an explicit bar — the freeze probes count DISTINCT bars. */
  private static SignalRejectionRepository.RejectionRow rowAtBar(
      String contextJson, OffsetDateTime barTime) {
    try {
      return new SignalRejectionRepository.RejectionRow(
          1, null, "slug", "NFO", "FUT", "3m", "CE", "volume-floor", null, null, null, "r",
          null, null, MAPPER.readTree("{\"context\":" + contextJson + "}"),
          null, false, barTime, OffsetDateTime.now());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * {@code count} rows on {@code count} DISTINCT 3m bars, context rendered per bar index — enough
   * bars to clear {@link DotHealthCanary#MIN_FROZEN_BARS} when count is 8 or more.
   */
  private static SignalRejectionRepository.RejectionRow[] rowsAcrossBars(
      int count, java.util.function.IntFunction<String> contextForBar) {
    OffsetDateTime base = OffsetDateTime.parse("2026-06-10T09:15:00+05:30");
    SignalRejectionRepository.RejectionRow[] rows =
        new SignalRejectionRepository.RejectionRow[count];
    for (int i = 0; i < count; i++) {
      rows[i] = rowAtBar(contextForBar.apply(i), base.plusMinutes(3L * i));
    }
    return rows;
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
          null, false, OffsetDateTime.now(), OffsetDateTime.now());
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
  void aFrozenOperandIsFlaggedEvenThoughTheDotReadsAlive() {
    // G12: the third input state. `vixLevel` is non-null on every bar, so the alive/dead probe is
    // right to call it live — and it carries ONE value all session, silently re-capping the
    // composite exactly as a dead input would.
    stubRows(rowsAcrossBars(8, i -> "{\"macro\":{\"vixLevel\":12.4,\"advances\":30,\"declines\":20}}"));
    DotHealthCanary.DotHealth health = canary("breadth").evaluate();

    assertThat(health.dots())
        .filteredOn(s -> s.dot().equals("vix"))
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.alive()).as("non-null on every bar — liveness is genuinely true").isTrue();
              assertThat(s.frozen()).isTrue();
              assertThat(s.detail()).contains("FROZEN").contains("across 8 bars");
            });
  }

  @Test
  void aVaryingOperandIsNotFrozen() {
    stubRows(rowsAcrossBars(8, i -> "{\"macro\":{\"vixLevel\":1" + i + ".4}}"));
    assertThat(canary("breadth").evaluate().dots())
        .filteredOn(s -> s.dot().equals("vix"))
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.frozen()).isFalse();
              assertThat(s.detail()).contains("input live").doesNotContain("FROZEN");
            });
  }

  @Test
  void oneBarFannedOutAcrossStrategiesIsNeverFrozen() {
    // THE guard on this feature. The engine evaluates one 3m bar across ~63 scalpers, so the 40-row
    // window can be a SINGLE bar whose macro context is identical by construction. Counting rows
    // instead of distinct bars would read that as a session-long freeze — the same fan-out
    // inflation that made the champion book's 24 rows look like 24 independent observations when
    // they were ~6. Twenty identical rows, ONE bar: not frozen.
    OffsetDateTime oneBar = OffsetDateTime.parse("2026-06-10T09:15:00+05:30");
    SignalRejectionRepository.RejectionRow[] fanOut =
        new SignalRejectionRepository.RejectionRow[20];
    java.util.Arrays.setAll(
        fanOut, i -> rowAtBar("{\"macro\":{\"vixLevel\":12.4}}", oneBar));
    stubRows(fanOut);

    assertThat(canary("breadth").evaluate().dots())
        .filteredOn(s -> s.dot().equals("vix"))
        .singleElement()
        .satisfies(s -> assertThat(s.frozen()).as("1 distinct bar < MIN_FROZEN_BARS").isFalse());
  }

  @Test
  void theAtmIvFreezeReadsByDesignAndNeverPages() {
    // G12's discovered case, and the reason `frozen` must not page. `atmIv` is the previous
    // session's EOD scalar: MarketOiClient.macro() -> /market/options/iv-history.currentIv ->
    // the last `iv_daily_summary` row, which IvRollupJob writes once a day at 16:00 IST. One value
    // per session is CORRECT, so a paging frozen-probe would fire a false alarm every single day.
    stubRows(rowsAcrossBars(8, i -> "{\"macro\":{\"atmIv\":0.118781,\"advances\":30,\"declines\":20}}"));
    DotHealthCanary canary = canary("iv_abs_band");

    assertThat(canary.evaluate().dots())
        .filteredOn(s -> s.dot().equals("iv_abs_band"))
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.alive()).isTrue();
              assertThat(s.frozen()).isTrue();
              assertThat(s.detail()).contains("BY DESIGN").contains("EOD daily operand");
            });

    canary.sweep();
    // A DAILY operand freezes every session by construction, so paging on it would be a guaranteed
    // daily false alarm. Only CONTINUOUS freezes page.
    verifyNoInteractions(events);
  }

  @Test
  void aLowCardinalityOperandIsNeverFreezeJudged() {
    // Cross-vendor review 2026-07-29: the quadrants are a 4-value enum and `dowUp` is a boolean, so
    // eight bars carrying the same value is their NORMAL state and says nothing. Judging them by
    // the continuous rule made the flag noise. The all-NEUTRAL OUTAGE is still caught — by `alive`,
    // which is the probe that pages.
    stubRows(
        rowsAcrossBars(
            10,
            i -> "{\"oi\":{\"futuresQuadrant\":\"NEUTRAL\",\"underlyingQuadrant\":\"NEUTRAL\"},"
                + "\"macro\":{\"advances\":30,\"declines\":20,\"dowUp\":true}}"));
    DotHealthCanary canary = canary("futures_oi");

    assertThat(canary.evaluate().dots())
        .filteredOn(s -> s.dot().equals("futures_oi") || s.dot().equals("dow"))
        .allSatisfy(s -> assertThat(s.frozen()).isFalse())
        .filteredOn(s -> s.dot().equals("futures_oi"))
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.alive()).as("the T13 outage is still caught").isFalse();
              assertThat(s.detail()).contains("input dead").doesNotContain("FROZEN");
            });

    canary.sweep();
    verify(events, times(1)).publishEvent(alertContaining("futures_oi DEAD"));
  }

  @Test
  void aFrozenContinuousRequiredOperandPagesOncePerDay() {
    // The other half of the review finding: a CONTINUOUS operand stuck on one value is an outage
    // `alive` cannot see (the field is populated), so without this it would only ever reach someone
    // who opened the rejections page. breadth is CONTINUOUS and required by default.
    stubRows(rowsAcrossBars(10, i -> "{\"macro\":{\"advances\":30,\"declines\":20}}"));
    DotHealthCanary canary = canary("breadth");

    assertThat(canary.evaluate().dots())
        .filteredOn(s -> s.dot().equals("breadth"))
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.alive()).as("populated on every bar").isTrue();
              assertThat(s.frozen()).isTrue();
            });

    canary.sweep();
    canary.sweep(); // same day — one page only
    verify(events, times(1)).publishEvent(alertContaining("breadth FROZEN"));
  }

  @Test
  void aDeadContinuousOperandPagesOnceAsDeadAndNeverAlsoAsFrozen() {
    // Cross-vendor review round 2: breadth's dead sentinel is 0/0, which fails the `alive` test but
    // still RENDERS a non-null operand — so eight bars of outage make the dot dead AND frozen. The
    // frozen page must therefore require alive(), or one outage emits two alerts and the second
    // one's message ("reads live but has not changed value") contradicts the first.
    stubRows(rowsAcrossBars(10, i -> "{\"macro\":{\"advances\":0,\"declines\":0}}"));
    DotHealthCanary canary = canary("breadth");

    canary.sweep();
    verify(events, times(1)).publishEvent(alertContaining("breadth DEAD"));
    verify(events, org.mockito.Mockito.never()).publishEvent(alertContaining("breadth FROZEN"));
  }

  @Test
  void expiryDaySuppressionStandsDownTheFrozenFlagToo() {
    // On a suppression day the OI read is not being produced at all, so its single inert value is
    // by-design inertness rather than a freeze. oi_spurt_price is CONTINUOUS (so it WOULD otherwise
    // be freeze-judged) and is in the S24 set, which makes it the probe that proves this branch.
    now.set(expiryDayAt11Ist());
    stubRows(
        rowsAcrossBars(
            10,
            i -> "{\"oi\":{\"futuresQuadrant\":\"NEUTRAL\",\"underlyingQuadrant\":\"NEUTRAL\","
                + "\"spurtPricePct\":0},\"macro\":{\"advances\":30,\"declines\":20},"
                + "\"underlying\":\"NIFTY 50\"}"));

    assertThat(canary("oi_spurt_price").evaluate().dots())
        .filteredOn(s -> s.dot().equals("oi_spurt_price"))
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.frozen()).isFalse();
              assertThat(s.detail()).contains("by design").doesNotContain("FROZEN");
            });
  }

  @Test
  void theFreezeWindowReachesPastTheFortyRowLivenessWindow() {
    // Cross-vendor review 2026-07-29, the finding that made the feature real: the freeze pass reads
    // EVERY context-bearing row in the scan, not the 40-row liveness window. Live fan-out put only
    // 4-7 distinct bars inside the newest 40 rows on 3 of 8 sessions (07-21/07-24/07-28), so a
    // 40-row-capped freeze pass was silently inert on a third of sessions however frozen the
    // operand was. Here: 60 rows of fan-out on 2 bars, then 8 further bars behind them. Capped at
    // 40 rows the probe sees 2 bars and cannot flag; reading the whole scan it sees 10.
    OffsetDateTime base = OffsetDateTime.parse("2026-06-10T09:15:00+05:30");
    List<SignalRejectionRepository.RejectionRow> rows = new java.util.ArrayList<>();
    for (int i = 0; i < 60; i++) {
      rows.add(rowAtBar("{\"macro\":{\"vixLevel\":12.4}}", base.plusMinutes(3L * (i % 2))));
    }
    for (int b = 2; b < 10; b++) {
      rows.add(rowAtBar("{\"macro\":{\"vixLevel\":12.4}}", base.plusMinutes(3L * b)));
    }
    stubRows(rows.toArray(new SignalRejectionRepository.RejectionRow[0]));

    assertThat(canary("breadth").evaluate().dots())
        .filteredOn(s -> s.dot().equals("vix"))
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.frozen()).isTrue();
              assertThat(s.detail()).contains("across 10 bars");
            });
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

  /** A breadth-scoring diagnostic: the macro operand plus the persisted per-dot supports verdict. */
  private static com.fasterxml.jackson.databind.JsonNode breadthDiagnostic(
      int advances, boolean supports) {
    try {
      return MAPPER.readTree(
          "{\"context\":{\"macro\":{\"advances\":" + advances
              + ",\"declines\":20}},\"confluence\":{\"dots\":[{\"dot\":\"breadth\","
              + "\"weight\":1.0,\"supports\":" + supports
              + ",\"reason\":\"advances/declines > 32\"}]}}");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** {@code count} CE rows on distinct 3m bars — the BOUNDED-window shape (liveness/freeze feed). */
  private static SignalRejectionRepository.RejectionRow[] breadthScoredRows(
      int count, java.util.function.IntUnaryOperator advancesForBar,
      java.util.function.IntPredicate supportsForBar) {
    OffsetDateTime base = OffsetDateTime.parse("2026-06-10T09:15:00+05:30");
    SignalRejectionRepository.RejectionRow[] rows =
        new SignalRejectionRepository.RejectionRow[count];
    for (int i = 0; i < count; i++) {
      rows[i] = new SignalRejectionRepository.RejectionRow(
          1, null, "slug", "NFO", "FUT", "3m", "CE", "confluence-composite", null, null, null,
          "r", null, null, breadthDiagnostic(advancesForBar.applyAsInt(i), supportsForBar.test(i)),
          null, false, base.plusMinutes(3L * i), OffsetDateTime.now());
    }
    return rows;
  }

  /**
   * {@code count} SESSION-WIDE (bar, side) verdicts — what
   * {@code sessionBarSideDiagnostics} returns after the DB's DISTINCT ON has collapsed fan-out.
   */
  private static List<SignalRejectionRepository.BarSideDiagnostic> breadthVerdicts(
      int count, java.util.function.IntUnaryOperator advancesForBar,
      java.util.function.IntPredicate supportsForBar) {
    OffsetDateTime base = OffsetDateTime.parse("2026-06-10T09:15:00+05:30");
    List<SignalRejectionRepository.BarSideDiagnostic> out = new java.util.ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      out.add(
          new SignalRejectionRepository.BarSideDiagnostic(
              base.plusMinutes(3L * i), "CE",
              breadthDiagnostic(advancesForBar.applyAsInt(i), supportsForBar.test(i))));
    }
    return out;
  }

  private void stubSession(List<SignalRejectionRepository.BarSideDiagnostic> verdicts) {
    when(rejections.sessionBarSideDiagnostics(any(), any())).thenReturn(verdicts);
  }

  private DotHealthCanary.DotState breadthState(DotHealthCanary canary) {
    return canary.evaluate().dots().stream()
        .filter(s -> s.dot().equals("breadth"))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void aLiveMovingNeverCrossingOperandFlagsNearMissAndNeverPages() {
    // G16's discovered case, 2026-07-30: breadth 0/814 supports off a completely healthy input —
    // moving values (10 distinct), rule `advances > 32`, session max EXACTLY 32. The liveness probe
    // is right (alive), the G12 probe is right (not frozen) — and the dot still contributed a
    // constant all session. THIS is the state the near-miss probe exists for.
    stubRows(breadthScoredRows(10, i -> 23 + i, i -> false)); // window: moving + alive
    stubSession(breadthVerdicts(10, i -> 23 + i, i -> false)); // session: advances 23..32, 0 support
    DotHealthCanary canary = canary("breadth");

    DotHealthCanary.DotState breadth = breadthState(canary);
    assertThat(breadth.alive()).as("input genuinely live").isTrue();
    assertThat(breadth.frozen()).as("moving values — not the G12 state").isFalse();
    assertThat(breadth.neverCrossing()).isTrue();
    assertThat(breadth.detail())
        .contains("NEAR-MISS")
        .contains("0/10 distinct (bar,side) verdicts")
        .contains("session max 32")
        .contains("never crossing");

    // Telemetry only: the dot is alive and not frozen, and the near-miss state itself never pages
    // (the threshold is doctrine; whether a near-miss deserves an alert cadence is an owner call).
    canary.sweep();
    verifyNoInteractions(events);
  }

  @Test
  void theNearMissEvidenceIsSessionWideNotTheBoundedLivenessWindow() {
    // Cross-vendor review Major 1, and the reason this probe does not reuse the bounded window: a
    // crossing at 09:25 falls off the newest-FETCH_DEPTH tail under fan-out, so a window-fed probe
    // would flag "session max 32, never crossed" hours after the operand demonstrably crossed at
    // 40. The mock plays the DB's role exactly: `list` returns the truncated newest rows (no
    // crossing), `sessionBarSideDiagnostics` returns the whole day (crossing included).
    stubRows(breadthScoredRows(30, i -> 30 + (i % 3), i -> false)); // tail: advances 30..32, none
    // Bar-ordered, exactly as the query returns it: the crossings sit on the EARLIEST three bars
    // (09:15-09:21, advances 40) and every later bar hugs the line. A newest-N tail therefore sees
    // a textbook near-miss; the session sees a dot that already crossed decisively.
    stubSession(
        breadthVerdicts(33, i -> i < 3 ? 40 : 30 + (i % 3), i -> i < 3));

    DotHealthCanary.DotState breadth = breadthState(canary("breadth"));
    assertThat(breadth.alive()).isTrue();
    assertThat(breadth.frozen()).isFalse();
    assertThat(breadth.neverCrossing())
        .as("the dot crossed decisively earlier today — the tail simply cannot see it")
        .isFalse();
    assertThat(breadth.detail()).doesNotContain("NEAR-MISS");
  }

  @Test
  void aLoneCrossingDoesNotVetoTheNearMissState() {
    // Cross-vendor review Major 2: the tolerance exists so the DISCOVERED 0.2%-support session
    // still flags. Strictness would preserve exactly the blindness G16 was filed to kill, and this
    // never pages, so a false negative is the expensive direction. 2 of 200 verdicts cross (at 33,
    // one point over the line) — 1% <= the 2% tolerance, and the extremum still hugs the line.
    stubRows(breadthScoredRows(10, i -> 30 + (i % 3), i -> false));
    stubSession(breadthVerdicts(200, i -> i < 2 ? 33 : 23 + (i % 10), i -> i < 2));

    DotHealthCanary.DotState breadth = breadthState(canary("breadth"));
    assertThat(breadth.neverCrossing()).isTrue();
    assertThat(breadth.detail())
        .contains("NEAR-MISS")
        .contains("2/200 distinct (bar,side) verdicts")
        .contains("session max 33");
  }

  @Test
  void aNormallyDiscriminatingDotIsNotNearMiss() {
    // Mixed supports well past the tolerance = the dot discriminated today, however close the
    // extremum sits to the line.
    stubRows(breadthScoredRows(10, i -> 28 + i, i -> 28 + i > 32));
    stubSession(breadthVerdicts(10, i -> 28 + i, i -> 28 + i > 32)); // advances 28..37, 5 crossings
    assertThat(breadthState(canary("breadth")).neverCrossing()).isFalse();
  }

  @Test
  void aZeroSupportDayFarFromTheLineIsRegimeNotNearMiss() {
    // The 2026-07-31 oi_spurt shape (§3.28 check: "not never-crossing-by-threshold — conjunct-
    // starved, regime"): 0% support with the operand nowhere near the line is a market verdict,
    // not a telemetry state. Max advances 14 against > 32 (distance 18 > epsilon 3) must NOT flag
    // — this is what separates the detector from re-litigating the threshold.
    stubRows(breadthScoredRows(10, i -> 5 + i, i -> false));
    stubSession(breadthVerdicts(10, i -> 5 + i, i -> false)); // advances 5..14
    DotHealthCanary.DotState breadth = breadthState(canary("breadth"));
    assertThat(breadth.neverCrossing()).isFalse();
    assertThat(breadth.detail()).doesNotContain("NEAR-MISS");
  }

  @Test
  void aRareButDecisiveCrossingIsNotNearMiss() {
    // The other half of the tolerance: a dot that fires rarely but DECISIVELY (operand 50 against
    // a > 32 line) is discriminating, not hugging the line. Same 1% support rate as the flagging
    // case above — only the extremum's distance separates them.
    stubRows(breadthScoredRows(10, i -> 30 + (i % 3), i -> false));
    stubSession(breadthVerdicts(200, i -> i < 2 ? 50 : 23 + (i % 10), i -> i < 2));
    assertThat(breadthState(canary("breadth")).neverCrossing()).isFalse();
  }

  @Test
  void aFrozenDotStaysFrozenAndIsNotAlsoNearMiss() {
    // The states are disjoint by construction: one distinct value is the G12 freeze (already
    // reported and, for a required CONTINUOUS dot, already paged) — re-flagging it as a near miss
    // would double-report one defect under two names.
    stubRows(breadthScoredRows(10, i -> 30, i -> false)); // one distinct value, 2 under the line
    stubSession(breadthVerdicts(10, i -> 30, i -> false));
    DotHealthCanary.DotState breadth = breadthState(canary("breadth"));
    assertThat(breadth.frozen()).isTrue();
    assertThat(breadth.neverCrossing()).isFalse();
    assertThat(breadth.detail()).contains("FROZEN").doesNotContain("NEAR-MISS");
  }

  @Test
  void theAlwaysCrossingMirrorFlagsWithinEpsilonAboveTheLine() {
    // The ~100% mirror: every verdict supported, session min just over the line — the dot never
    // discriminates either, it adds a constant weight from the other side.
    stubRows(breadthScoredRows(10, i -> 33 + (i % 3), i -> true));
    stubSession(breadthVerdicts(10, i -> 33 + (i % 3), i -> true)); // advances 33..35, min 33
    DotHealthCanary.DotState breadth = breadthState(canary("breadth"));
    assertThat(breadth.neverCrossing()).isTrue();
    assertThat(breadth.detail())
        .contains("NEAR-MISS")
        .contains("session min 33")
        .contains("never discriminating");
  }

  @Test
  void tooFewScoredVerdictsAbstainFromTheNearMissAssertion() {
    // Same discipline as MIN_FROZEN_BARS: the flag is an assertion, and an un-evidenced assertion
    // must read false. A perfect near-miss signature on 5 verdicts is noise, not a state.
    stubRows(breadthScoredRows(10, i -> 28 + (i % 5), i -> false));
    stubSession(breadthVerdicts(5, i -> 28 + i, i -> false)); // 5 < MIN_NEAR_MISS_VERDICTS
    assertThat(breadthState(canary("breadth")).neverCrossing()).isFalse();
  }

  @Test
  void aDeadOrFrozenDotNeverPaysForTheSessionWideRead() {
    // The session read is an EXTRA query on a 60s-polled endpoint, so it must not ride on every
    // request. A dead breadth (0/0 sentinel) cannot reach the near-miss branch at all.
    stubRows(row("{\"macro\":{\"advances\":0,\"declines\":0}}"));
    canary("breadth").evaluate();
    verify(rejections, org.mockito.Mockito.never()).sessionBarSideDiagnostics(any(), any());
  }
}
