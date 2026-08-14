package in.arthayantra.marketdata.canary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.constituents.StaticIndexConstituents;
import in.arthayantra.marketdata.kite.GapBackfiller;
import in.arthayantra.marketdata.canary.BhavcopyCloseCanary.CloseMismatch;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;

/**
 * The coverage floor: a comparison population too small to mean anything must never read GREEN.
 *
 * <p>⚠️ Why this exists. This canary never fetched its own Kite 1d bars — it compares whatever
 * {@code source='KITE'} rows another job left in {@code candles}. #1333 moved the swing batch from a
 * 20:00 full-screen run to a 16:00 exits-only settle, and the population it incidentally left behind
 * went from 171 / 159 / 160 / 164 symbols (2026-08-05..08-10) to <b>14 / 14</b> (08-11 / 08-12),
 * against an EQ bhavcopy universe of ~2 450. Severity keyed only on the divergent count, so
 * 2026-08-11 reported <b>GREEN on 14 symbols</b> — catalogue trap #14, an armed gate whose operand
 * is structurally near-zero. At 14 comparable symbols a {@code redFloor} of 20 was not merely
 * unlikely but arithmetically unreachable.
 *
 * <p>A unit test because the floor is pure verdict arithmetic over two counts; the realistic
 * end-to-end case (a seeded population that agrees perfectly, against real SQL and a real schema)
 * lives in {@code DataQualityCanaryIntegrationTest#v8ThinPopulationIsNotCertifiedGreen}.
 */
class BhavcopyCloseCanaryCoverageFloorTest {

  private static final LocalDate DAY = LocalDate.of(2026, 8, 11);
  private static final int FLOOR = 100;

  @Test
  @DisplayName("14 comparable symbols with zero divergence is YELLOW, not GREEN — the live case")
  void thinPopulationWithNoDivergenceIsNotGreen() {
    // The exact shape of 2026-08-11: everything the canary could see agreed, and there were 14 of
    // them out of ~2450. Before the floor this was the GREEN that hid the collapse.
    var report = canary(14, 0, FLOOR).evaluate(DAY);

    assertThat(report.status())
        .as("14 of ~2450 symbols agreeing certifies nothing; GREEN here is the defect")
        .isEqualTo("YELLOW");
    assertThat(report.compared()).isEqualTo(14);
    assertThat(report.divergent()).isZero();
    assertThat(report.minCompared())
        .as("the report must carry the floor, or a reader cannot tell a coverage YELLOW from a"
            + " divergence YELLOW")
        .isEqualTo(FLOOR);
  }

  @Test
  @DisplayName("an empty comparison set is YELLOW — 'nothing to compare' is not 'the feeds agree'")
  void emptyPopulationIsNotGreen() {
    assertThat(canary(0, 0, FLOOR).evaluate(DAY).status()).isEqualTo("YELLOW");
    // The null-date path (an empty bhavcopy table) shares the same rule rather than short-
    // circuiting to GREEN, which is what it used to do.
    assertThat(canary(0, 0, FLOOR).evaluate(null).status()).isEqualTo("YELLOW");
  }

  @Test
  @DisplayName("one symbol below the floor is enough to withhold GREEN")
  void justBelowTheFloorIsNotGreen() {
    // ⚠️ Pins the boundary as < rather than <=. Without this the floor could be off by one and
    // every other test here would still pass.
    assertThat(canary(FLOOR - 1, 0, FLOOR).evaluate(DAY).status()).isEqualTo("YELLOW");
  }

  @Test
  @DisplayName("a healthy population with no divergence is still GREEN — not an off-switch")
  void healthyPopulationStaysGreen() {
    // ⚠️ Without this the floor could reject EVERYTHING and every assertion above would still
    // pass, which is a canary that reports failure while checking nothing — the mirror of the
    // defect being fixed.
    assertThat(canary(159, 0, FLOOR).evaluate(DAY).status())
        .as("159 was the measured healthy minimum (2026-08-06); it must certify")
        .isEqualTo("GREEN");
  }

  @Test
  @DisplayName("exactly at the floor is GREEN")
  void atTheFloorIsGreen() {
    assertThat(canary(FLOOR, 0, FLOOR).evaluate(DAY).status()).isEqualTo("GREEN");
  }

  @Test
  @DisplayName("a floor of 0 CANNOT disable the check — it is clamped, not honoured")
  void floorOfZeroCannotDisableTheCheck() {
    // ⚠️ This test asserted the OPPOSITE in the first round of this PR, and the reviewer was right
    // to call it: every SQL count is non-negative, so `compared < 0` can never fire and an EMPTY
    // population read GREEN again. A knob that reintroduces trap #14 inside the fix for trap #14,
    // with a test ratifying it. The floor is now clamped to max(1, redFloor).
    var report = canary(0, 0, 0).evaluate(DAY);

    assertThat(report.status())
        .as("an empty population must never certify, whatever the floor is configured to")
        .isEqualTo("YELLOW");
    assertThat(report.minCompared())
        .as("the report publishes the EFFECTIVE floor, so the clamp is visible in the artifact and"
            + " not only in a startup log line")
        .isEqualTo(20);
  }

  @Test
  @DisplayName("a floor below redFloor is raised to it — certifying implies RED was reachable")
  void floorBelowRedFloorIsRaised() {
    // divergent can never exceed compared, so at compared < redFloor the top severity cannot fire
    // however corrupt the feed is. Certification must imply RED was at least reachable.
    assertThat(canary(19, 0, 5).evaluate(DAY).minCompared()).isEqualTo(20);
    assertThat(canary(19, 0, 5).evaluate(DAY).status())
        .as("19 comparable symbols cannot reach a redFloor of 20, so this must not read GREEN")
        .isEqualTo("YELLOW");
  }

  @Test
  @DisplayName("a floor above redFloor is honoured as configured — the clamp is a floor, not a pin")
  void floorAboveRedFloorIsHonoured() {
    // ⚠️ Without this the clamp could overwrite EVERY configured value with redFloor and the two
    // tests above would still pass — a guard that silently ignores its own knob.
    assertThat(canary(150, 0, FLOOR).evaluate(DAY).minCompared()).isEqualTo(FLOOR);
  }

  @Test
  @DisplayName("a thin population does NOT downgrade RED to YELLOW")
  void thinPopulationDoesNotSoftenRed() {
    // ⚠️ The load-bearing one. Written as "short coverage ⇒ YELLOW, else the divergence rule", the
    // floor would SILENCE the worst verdict in exactly the situation it exists to flag — a fix
    // that reopens a hole. 25 divergent clears the redFloor of 20; coverage must not touch it.
    var report = canary(30, 25, FLOOR).evaluate(DAY);

    assertThat(report.status())
        .as("coverage may withhold GREEN; it must never soften a divergence verdict")
        .isEqualTo("RED");
    assertThat(report.compared()).isEqualTo(30);
    assertThat(report.divergent()).isEqualTo(25);
  }

  @Test
  @DisplayName("a thin population leaves a divergence YELLOW as it was")
  void thinPopulationLeavesDivergenceYellow() {
    var report = canary(14, 2, FLOOR).evaluate(DAY); // the 2026-08-12 shape
    assertThat(report.status()).isEqualTo("YELLOW");
    assertThat(report.divergent()).isEqualTo(2);
  }

  /**
   * A canary whose two queries are stubbed: the offender row-mapper query returns
   * {@code divergentCount} mismatches, and the {@code count(*)} returns {@code comparedCount}.
   */
  private static BhavcopyCloseCanary canary(int comparedCount, int divergentCount, int floor) {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<CloseMismatch>>any(), any(), any()))
        .thenReturn(offenders(divergentCount));
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(comparedCount);
    return new BhavcopyCloseCanary(
        jdbc,
        mock(NtfyClient.class),
        Clock.fixed(Instant.parse("2026-08-10T19:00:00Z"), ZoneOffset.UTC),
        mock(StaticIndexConstituents.class),
        mock(GapBackfiller.class),
        mock(MarketCalendar.class),
        new SimpleMeterRegistry(),
        new MockEnvironment().withProperty("spring.profiles.active", "live"),
        true,
        true,
        new BigDecimal("0.01"),
        20,
        25,
        floor,
        "0 5 16 * * MON-FRI");
  }

  private static List<CloseMismatch> offenders(int n) {
    return IntStream.range(0, n)
        .mapToObj(
            i ->
                new CloseMismatch(
                    "SYM" + i,
                    new BigDecimal("130"),
                    new BigDecimal("100"),
                    new BigDecimal("30.00")))
        .toList();
  }
}
