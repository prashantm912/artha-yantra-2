package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * IT for {@link PlaneDivergenceProbe}: a Minervini passer whose {@code nse_eod_bhavcopy} and
 * {@code candles}@1d closes disagree is reported; one whose planes agree is not; and a divergence
 * that only appeared AFTER the screen ran is excluded rather than reported.
 *
 * <p><b>The fixture is the gate.</b> Five passers are seeded so a probe that got ANY of the three
 * axes wrong gives a visibly different answer, rather than agreeing with the correct one:
 *
 * <ul>
 *   <li>{@code PDVCAND} — planes differ 9% on a bar fetched BEFORE the screen ran, and it is a
 *       SERVED candidate (buyable). The one row that must be reported as a candidate.
 *   <li>{@code PDVCLEAN} — a served candidate too, but the two planes are byte-identical on every
 *       bar. A probe that compared one plane against itself would report this one as well.
 *   <li>{@code PDVWATCH} — planes differ 9%, but no valid VCP base, so the funnel buckets it WATCH.
 *       Must be reported with {@code candidate=false}.
 *   <li>{@code PDVLATE} — <b>the as-of axis.</b> Identical to {@code PDVCAND} in every way except
 *       that its divergent candle bar carries {@code fetched_at} AFTER the screen's {@code
 *       computed_at} — a retro-rewrite the screen never saw. An UNGATED probe reports it exactly
 *       like {@code PDVCAND}; the gated probe must not report it at all, and must count its bar as
 *       excluded. This is the trap that produced four false flips in #1272.
 *   <li>{@code PDVTINY} — a served candidate at 0.2%, below the 0.5% report floor.
 * </ul>
 *
 * {@code PDVCAND} and {@code PDVLATE} are the pair a gated and an ungated implementation answer
 * <b>differently</b>; every other pair separates a different axis. Shares the singleton DB → purge
 * before AND after.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class PlaneDivergenceProbeIntegrationTest extends MarketDataIntegrationTestBase {

  private static final LocalDate AS_OF = LocalDate.of(2026, 6, 17);

  /** The screen's own persistence time — the as-of cutoff every bar pair is judged against. */
  private static final OffsetDateTime SCREEN_AT =
      OffsetDateTime.of(2026, 6, 17, 19, 55, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));

  private static final List<String> SYMS =
      List.of("PDVCAND", "PDVCLEAN", "PDVWATCH", "PDVLATE", "PDVTINY");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlaneDivergenceProbe probe;

  private void purge() {
    for (String s : SYMS) {
      jdbc.update("DELETE FROM minervini_screen_results WHERE symbol=?", s);
      jdbc.update("DELETE FROM minervini_setups WHERE symbol=?", s);
      jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol=?", s);
      jdbc.update("DELETE FROM candles WHERE exchange='NSE' AND tradingsymbol=?", s);
    }
    jdbc.update(
        "DELETE FROM canary_runs WHERE canary=? AND run_day=?",
        PlaneDivergenceProbe.CANARY_KEY, java.sql.Date.valueOf(AS_OF));
  }

  @AfterEach
  void tearDown() {
    purge();
  }

  @BeforeEach
  void seed() {
    purge();
    // (symbol, close, isVcp, pivot): close == pivot -> BUYABLE (served); no base -> WATCH.
    passer("PDVCAND", 100.0, true, 100.0);
    passer("PDVCLEAN", 100.0, true, 100.0);
    passer("PDVWATCH", 100.0, false, null);
    passer("PDVLATE", 100.0, true, 100.0);
    passer("PDVTINY", 100.0, true, 100.0);

    // Three sessions inside the lookback. Plane A (bhavcopy) is 100 on every bar for every symbol
    // and always fetched before the screen; plane B (candles) is what differs. No
    // eod_corporate_actions rows exist for these symbols, so the CA factor is 1 on both sides and
    // the ONLY things the probe can be measuring are the plane and the as-of gate.
    OffsetDateTime before = SCREEN_AT.minusHours(3); // the day's data landed at ~16:55 IST
    OffsetDateTime after = SCREEN_AT.plusDays(2); // a CorporateActionJob rewrite, two days later
    for (int back = 0; back < 3; back++) {
      LocalDate d = AS_OF.minusDays(back);
      for (String s : SYMS) {
        bhavcopyBar(s, d, 100.0, before);
      }
      boolean divergentBar = back == 1; // the middle bar carries the dividend adjustment
      candleBar("PDVCAND", d, divergentBar ? 91.0 : 100.0, before); // 9%, seen by the screen
      candleBar("PDVWATCH", d, divergentBar ? 91.0 : 100.0, before); // 9%, but WATCH bucket
      candleBar("PDVLATE", d, divergentBar ? 91.0 : 100.0, divergentBar ? after : before);
      candleBar("PDVCLEAN", d, 100.0, before);
      candleBar("PDVTINY", d, divergentBar ? 99.8 : 100.0, before); // 0.2% — under the floor
    }
  }

  @Test
  void reportsDivergentPassersAndFlagsOnlyTheServedCandidate() {
    PlaneDivergenceProbe.Report r = probe.probe(AS_OF);

    Map<String, PlaneDivergenceProbe.DivergentName> byName =
        r.names().stream()
            .filter(n -> SYMS.contains(n.symbol()))
            .collect(
                Collectors.toMap(PlaneDivergenceProbe.DivergentName::symbol, Function.identity()));

    // divergent vs non-divergent give OPPOSITE answers on the same fixture
    assertThat(byName.keySet()).containsExactlyInAnyOrder("PDVCAND", "PDVWATCH");
    assertThat(byName).doesNotContainKeys("PDVCLEAN", "PDVTINY");

    // the served candidate is flagged; the WATCH-bucket divergence is reported but not flagged
    assertThat(byName.get("PDVCAND").candidate()).isTrue();
    assertThat(byName.get("PDVWATCH").candidate()).isFalse();

    // magnitude + worst bar are the ones the fixture planted (max over the window, not last bar)
    assertThat(byName.get("PDVCAND").maxDivergencePct()).isEqualByComparingTo("9.0000");
    assertThat(byName.get("PDVCAND").worstBar()).isEqualTo(AS_OF.minusDays(1));
    assertThat(byName.get("PDVCAND").sharedBars()).isEqualTo(3);
    assertThat(byName.get("PDVCAND").barsExcludedAsOf()).isZero();
  }

  /**
   * The as-of gate. {@code PDVLATE}'s divergence is identical in size and shape to {@code
   * PDVCAND}'s; the ONLY difference is that its candle bar was rewritten two days after the screen
   * ran. Reporting it would be reporting a divergence the screen never saw.
   */
  @Test
  void aDivergenceThatAppearedAfterTheScreenRanIsExcludedNotReported() {
    PlaneDivergenceProbe.Report r = probe.probe(AS_OF);

    assertThat(r.asOfCutoff()).isEqualTo(SCREEN_AT);

    List<String> reported =
        r.names().stream()
            .map(PlaneDivergenceProbe.DivergentName::symbol)
            .filter(SYMS::contains)
            .toList();
    // the pair that separates a gated probe from an ungated one
    assertThat(reported).contains("PDVCAND").doesNotContain("PDVLATE");

    // PDVLATE is still JUDGED — on its two honest bars, which agree — so it is neither reported as
    // divergent nor counted as unjudgeable; only its rewritten bar is excluded.
    assertThat(seededExcluded(r)).isEqualTo(1);
    assertThat(r.barsExcludedAsOf()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void reportShapeAndFloors() {
    PlaneDivergenceProbe.Report r = probe.probe(AS_OF);

    long seededDivergent = r.names().stream().filter(n -> SYMS.contains(n.symbol())).count();
    long seededCandidates =
        r.names().stream().filter(n -> SYMS.contains(n.symbol()) && n.candidate()).count();

    // three seeded names carry a >=0.5% divergence in the raw data; only TWO survive the as-of
    // gate, and only ONE of those is a served candidate.
    assertThat(seededDivergent).isEqualTo(2);
    assertThat(seededCandidates).isEqualTo(1);
    assertThat(r.thresholdPct()).isEqualByComparingTo("0.5");
    assertThat(r.lookbackDays()).isEqualTo(420);
    assertThat(r.screenDate()).isEqualTo(AS_OF);
  }

  /**
   * <b>The state that took three review rounds to close.</b> Completion is DERIVED from the screen,
   * not flagged: a marker counts only while it is at least as new as the screen's {@code
   * computed_at}. The two halves are the same marker moved five minutes either side of the screen —
   * a flag-based implementation answers {@code true} to both, a derived one splits them.
   *
   * <p>Why it matters: after a forced recompute whose probe then FAILED (or was disabled), the old
   * marker survives. Under a flag it suppresses every later door forever — the durability mechanism
   * silently destroying the observation it exists to protect. Under derivation the recompute has
   * already moved {@code computed_at} past it, so the date re-opens with no invalidation write.
   */
  @Test
  void aMarkerOlderThanTheScreenDoesNotCountAsComplete() {
    // stale: reported BEFORE the screen was (re)computed -> describes a screen that no longer exists
    writeMarker(AS_OF, SCREEN_AT.minusMinutes(5));
    assertThat(probe.alreadyReported(AS_OF)).isFalse();

    // fresh: reported after -> genuinely describes the current screen
    writeMarker(AS_OF, SCREEN_AT.plusMinutes(5));
    assertThat(probe.alreadyReported(AS_OF)).isTrue();

    clearMarker(AS_OF);
  }

  /** A marker can never suppress a date that was never screened (NULL {@code computed_at}). */
  @Test
  void aMarkerForANeverScreenedDateDoesNotCountAsComplete() {
    LocalDate neverScreened = LocalDate.of(2019, 1, 4);
    clearMarker(neverScreened);
    writeMarker(neverScreened, SCREEN_AT.plusYears(5));

    assertThat(probe.alreadyReported(neverScreened)).isFalse();

    clearMarker(neverScreened);
  }

  /** The completion marker the scheduler retries on — recorded once, idempotent. */
  @Test
  void completionMarkerIsRecordedAndIdempotent() {
    jdbc.update(
        "DELETE FROM canary_runs WHERE canary=? AND run_day=?",
        PlaneDivergenceProbe.CANARY_KEY,
        java.sql.Date.valueOf(AS_OF));
    assertThat(probe.alreadyReported(AS_OF)).isFalse();

    probe.markReported(AS_OF);
    probe.markReported(AS_OF); // ON CONFLICT DO UPDATE — a retry must not blow up

    assertThat(probe.alreadyReported(AS_OF)).isTrue();
    jdbc.update(
        "DELETE FROM canary_runs WHERE canary=? AND run_day=?",
        PlaneDivergenceProbe.CANARY_KEY,
        java.sql.Date.valueOf(AS_OF));
  }

  private void writeMarker(LocalDate day, OffsetDateTime completedAt) {
    jdbc.update(
        "INSERT INTO canary_runs(canary, run_day, state, source, claimed_at, completed_at)"
            + " VALUES(?,?, 'DONE', 'TEST', ?, ?)"
            + " ON CONFLICT (canary, run_day) DO UPDATE SET state='DONE', completed_at=EXCLUDED.completed_at",
        PlaneDivergenceProbe.CANARY_KEY, java.sql.Date.valueOf(day), completedAt, completedAt);
  }

  private void clearMarker(LocalDate day) {
    jdbc.update(
        "DELETE FROM canary_runs WHERE canary=? AND run_day=?",
        PlaneDivergenceProbe.CANARY_KEY, java.sql.Date.valueOf(day));
  }

  /** Bar-pairs among the seeded symbols where either side post-dates the screen's cutoff. */
  private int seededExcluded(PlaneDivergenceProbe.Report r) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM candles c
            JOIN nse_eod_bhavcopy b
              ON b.symbol = c.tradingsymbol
             AND b.trade_date = (c.bucket AT TIME ZONE 'Asia/Kolkata')::date
            WHERE c.exchange='NSE' AND c."interval"='1d'
              AND c.tradingsymbol = ANY (?)
              AND (c.fetched_at > ? OR b.fetched_at > ?)
            """,
            Integer.class,
            SYMS.toArray(new String[0]),
            r.asOfCutoff(),
            r.asOfCutoff());
    return n == null ? 0 : n;
  }

  private void passer(String symbol, double close, boolean isVcp, Double pivot) {
    jdbc.update(
        "INSERT INTO minervini_screen_results(screen_date,symbol,exchange,close_price,rs_rank,"
            + "gate1,gate2,gate3,gate4,gate5,gate6,gate7,gate8,gates_passed,passes_all,stage,"
            + "computed_at) "
            + "VALUES(?,?, 'NSE', ?, 90, TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE, 8, TRUE, 2, ?) "
            + "ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(AS_OF), symbol, close, SCREEN_AT);
    jdbc.update(
        "INSERT INTO minervini_setups(screen_date,symbol,is_vcp,pivot,footprint) "
            + "VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(AS_OF), symbol, isVcp, pivot, isVcp ? "8W 8/4 2T" : null);
  }

  private void bhavcopyBar(String symbol, LocalDate d, double close, OffsetDateTime fetchedAt) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy(trade_date,symbol,series,open_price,high_price,low_price,"
            + "close_price,ttl_trd_qnty,fetched_at) VALUES(?,?, 'EQ', ?,?,?,?, 1000000, ?) "
            + "ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(d), symbol, close, close, close, close, fetchedAt);
  }

  private void candleBar(String symbol, LocalDate d, double close, OffsetDateTime fetchedAt) {
    // source='KITE' — the plane that silently acquires the dividend adjustment. Deliberately NOT
    // 'BHAVCOPY': EquitySplitBonusAdjuster (and this probe) scale only BHAVCOPY-sourced bars, so a
    // KITE bar carries whatever the broker sent, which is the whole mechanism under test.
    OffsetDateTime bucket = d.atStartOfDay().atOffset(ZoneOffset.ofHoursMinutes(5, 30));
    jdbc.update(
        "INSERT INTO candles(exchange,tradingsymbol,\"interval\",bucket,open,high,low,close,volume,"
            + "source,fetched_at) VALUES('NSE',?, '1d', ?, ?,?,?,?, 1000000, 'KITE', ?) "
            + "ON CONFLICT DO NOTHING",
        symbol, bucket, close, close, close, close, fetchedAt);
  }
}
