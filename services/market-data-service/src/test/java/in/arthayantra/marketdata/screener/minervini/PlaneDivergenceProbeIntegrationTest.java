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
 * {@code candles}@1d closes disagree is reported, and one whose planes agree is not.
 *
 * <p><b>The fixture is the gate.</b> Five passers are seeded so that a probe which got ANY of the
 * three axes wrong gives a visibly different answer, rather than agreeing with the correct one:
 *
 * <ul>
 *   <li>{@code PDVCAND} — planes differ 9%, and it is a SERVED candidate (buyable). The only row
 *       that may raise the alarm.
 *   <li>{@code PDVCLEAN} — a served candidate too, but the two planes are byte-identical on every
 *       bar. A probe that compared one plane against itself (or ignored the candles source-aware
 *       branch) would report this one as well — it must be absent.
 *   <li>{@code PDVWATCH} — planes differ 9%, but no valid VCP base, so the funnel buckets it WATCH.
 *       A probe that alarmed on "a divergence exists" instead of "a divergence reached a served
 *       candidate" would count it — it must be reported with {@code candidate=false}.
 *   <li>{@code PDVMILD} — a served candidate at 2.0%: over the report floor, under the page floor.
 *       A probe with ONE threshold instead of two would alarm on it. This is the case that actually
 *       occurs — measured at ~4.7 such candidates on every screen date.
 *   <li>{@code PDVTINY} — a served candidate whose planes differ by 0.2%, below the report floor. A
 *       probe with no threshold would report it.
 * </ul>
 *
 * The divergent and non-divergent symbols therefore give OPPOSITE answers on all three axes:
 * divergence, served-candidate, and page floor. Shares the singleton DB → purge before AND after.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class PlaneDivergenceProbeIntegrationTest extends MarketDataIntegrationTestBase {

  private static final LocalDate AS_OF = LocalDate.of(2026, 6, 17);
  private static final List<String> SYMS =
      List.of("PDVCAND", "PDVCLEAN", "PDVWATCH", "PDVTINY", "PDVMILD");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlaneDivergenceProbe probe;

  private void purge() {
    for (String s : SYMS) {
      jdbc.update("DELETE FROM minervini_screen_results WHERE symbol=?", s);
      jdbc.update("DELETE FROM minervini_setups WHERE symbol=?", s);
      jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol=?", s);
      jdbc.update("DELETE FROM candles WHERE exchange='NSE' AND tradingsymbol=?", s);
    }
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
    passer("PDVTINY", 100.0, true, 100.0);
    passer("PDVMILD", 100.0, true, 100.0);

    // Three sessions inside the lookback. Plane A (bhavcopy) is 100 on every bar for every symbol;
    // plane B (candles) is what differs. No eod_corporate_actions rows exist for these symbols, so
    // the CA factor is 1 on both sides and the ONLY thing the probe can be measuring is the plane.
    for (int back = 0; back < 3; back++) {
      LocalDate d = AS_OF.minusDays(back);
      for (String s : SYMS) {
        bhavcopyBar(s, d, 100.0);
      }
      // PDVCAND / PDVWATCH: the middle bar is dividend-back-adjusted on the candles plane (91.00 =
      // a 9% divergence, the INDOBORAX shape). The other two bars agree, so the probe must be
      // taking a MAX over the window, not just reading the latest bar.
      candleBar("PDVCAND", d, back == 1 ? 91.0 : 100.0);
      candleBar("PDVWATCH", d, back == 1 ? 91.0 : 100.0);
      candleBar("PDVCLEAN", d, 100.0);
      candleBar("PDVTINY", d, back == 1 ? 99.8 : 100.0); // 0.2% — under the 0.5% report floor
      candleBar("PDVMILD", d, back == 1 ? 98.0 : 100.0); // 2.0% — reported, but under the 5% page floor
    }
  }

  @Test
  void reportsDivergentPassersAndFlagsOnlyTheServedCandidate() {
    PlaneDivergenceProbe.Report r = probe.probe(AS_OF);

    Map<String, PlaneDivergenceProbe.DivergentName> byName =
        r.names().stream()
            .filter(n -> SYMS.contains(n.symbol()))
            .collect(Collectors.toMap(PlaneDivergenceProbe.DivergentName::symbol, Function.identity()));

    // divergent vs non-divergent give OPPOSITE answers on the same fixture
    assertThat(byName.keySet()).containsExactlyInAnyOrder("PDVCAND", "PDVWATCH", "PDVMILD");
    assertThat(byName).doesNotContainKeys("PDVCLEAN", "PDVTINY");

    // the served candidates are flagged; the WATCH-bucket divergence is reported but not flagged
    assertThat(byName.get("PDVCAND").candidate()).isTrue();
    assertThat(byName.get("PDVMILD").candidate()).isTrue();
    assertThat(byName.get("PDVWATCH").candidate()).isFalse();

    // the PAGE floor is a SECOND axis: PDVMILD is a served candidate and is NOT alerting
    assertThat(r.isAlerting(byName.get("PDVCAND"))).isTrue();
    assertThat(r.isAlerting(byName.get("PDVMILD"))).isFalse();
    assertThat(r.isAlerting(byName.get("PDVWATCH"))).isFalse();

    // magnitude + worst bar are the ones the fixture planted (max over the window, not last bar)
    assertThat(byName.get("PDVCAND").maxDivergencePct()).isEqualByComparingTo("9.0000");
    assertThat(byName.get("PDVCAND").worstBar()).isEqualTo(AS_OF.minusDays(1));
    assertThat(byName.get("PDVCAND").sharedBars()).isEqualTo(3);
  }

  @Test
  void alarmCountsOnlyServedCandidates() {
    PlaneDivergenceProbe.Report r = probe.probe(AS_OF);

    long seededDivergent = r.names().stream().filter(n -> SYMS.contains(n.symbol())).count();
    long seededCandidates =
        r.names().stream().filter(n -> SYMS.contains(n.symbol()) && n.candidate()).count();
    long seededAlerting =
        r.names().stream().filter(n -> SYMS.contains(n.symbol()) && r.isAlerting(n)).count();

    // three seeded names diverge; two are served candidates; only ONE clears the page floor.
    // A probe that alarmed on "a divergence exists" reports 3; one with a single threshold reports 2.
    assertThat(seededDivergent).isEqualTo(3);
    assertThat(seededCandidates).isEqualTo(2);
    assertThat(seededAlerting).isEqualTo(1);
    assertThat(r.thresholdPct()).isEqualByComparingTo("0.5");
    assertThat(r.alertPct()).isEqualByComparingTo("5.0");
    assertThat(r.lookbackDays()).isEqualTo(420);
  }

  private void passer(String symbol, double close, boolean isVcp, Double pivot) {
    jdbc.update(
        "INSERT INTO minervini_screen_results(screen_date,symbol,exchange,close_price,rs_rank,"
            + "gate1,gate2,gate3,gate4,gate5,gate6,gate7,gate8,gates_passed,passes_all,stage) "
            + "VALUES(?,?, 'NSE', ?, 90, TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE, 8, TRUE, 2) "
            + "ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(AS_OF), symbol, close);
    jdbc.update(
        "INSERT INTO minervini_setups(screen_date,symbol,is_vcp,pivot,footprint) "
            + "VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(AS_OF), symbol, isVcp, pivot, isVcp ? "8W 8/4 2T" : null);
  }

  private void bhavcopyBar(String symbol, LocalDate d, double close) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy(trade_date,symbol,series,open_price,high_price,low_price,"
            + "close_price,ttl_trd_qnty) VALUES(?,?, 'EQ', ?,?,?,?, 1000000) "
            + "ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(d), symbol, close, close, close, close);
  }

  private void candleBar(String symbol, LocalDate d, double close) {
    // source='KITE' — the plane that silently acquires the dividend adjustment. Deliberately NOT
    // 'BHAVCOPY': EquitySplitBonusAdjuster (and this probe) scale only BHAVCOPY-sourced bars, so a
    // KITE bar carries whatever the broker sent, which is the whole mechanism under test.
    OffsetDateTime bucket =
        d.atStartOfDay().atOffset(ZoneOffset.ofHoursMinutes(5, 30));
    jdbc.update(
        "INSERT INTO candles(exchange,tradingsymbol,\"interval\",bucket,open,high,low,close,volume,"
            + "source) VALUES('NSE',?, '1d', ?, ?,?,?,?, 1000000, 'KITE') ON CONFLICT DO NOTHING",
        symbol, bucket, close, close, close, close);
  }
}
