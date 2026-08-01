package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate.RejectionDiagnostic;
import in.arthayantra.strategysignal.scalper.StrikePicker;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shadow book IT (mock profile, real V016 lineage): an eligible rejection opens EXACTLY ONE virtual
 * position (dedup per strategy+side), ineligible ones don't, and the exit sweep closes on the
 * premium brackets / the signal-future structural stop / the 15:12 square-off / the prior-day STALE
 * rule with the PnL label. Redis last-ticks are seeded directly (the PaperLedger IT pattern); the
 * chain fallback is never needed here. Shared singleton DB — every symbol/slug is unique per method.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.signals.engine-enabled=false",
      "artha.scalper.shadow-book.enabled=true",
      // keep the real scheduler idle during the IT — the test drives evaluate() directly
      "artha.scalper.shadow-book.poll-ms=3600000"
    })
class ShadowBookIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private ShadowBookService shadowBook;
  @Autowired private ShadowExitMonitor monitor;
  @Autowired private ShadowPositionRepository shadows;
  @Autowired private SignalRejectionRepository rejections;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;
  @Autowired private ObjectMapper objectMapper;

  private UUID versionId() {
    return jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
  }

  private long rejection(UUID versionId, String slug, String futSym) {
    return rejections.insert(
        versionId, slug, "NFO", futSym, "3m", "CE", "volume-floor", new BigDecimal("7865"),
        new BigDecimal("125000"), new BigDecimal("-117135"), "volume < 125000",
        new BigDecimal("0.70"), new BigDecimal("0.60"), "{}", OffsetDateTime.now(), null, false);
  }

  private RejectionDiagnostic diag(String optSym, BigDecimal composite, BigDecimal entryLtp,
      BigDecimal structuralStop) {
    return diag(optSym, composite, entryLtp, structuralStop, "NFO");
  }

  /** Same diagnostic with the picked leg's exchange stated — lets a test contradict the root name. */
  private RejectionDiagnostic diag(String optSym, BigDecimal composite, BigDecimal entryLtp,
      BigDecimal structuralStop, String legExchange) {
    return new RejectionDiagnostic(
        "volume-floor", OptionType.CE, new BigDecimal("7865"), new BigDecimal("125000"),
        new BigDecimal("-117135"), "volume < 125000", composite, new BigDecimal("0.60"),
        List.of(), null, null, "NIFTY 50", LocalDate.now().plusDays(5),
        new StrikePicker.Pick(
            new StrikePicker.Candidate(
                legExchange, optSym, new BigDecimal("24250"), OptionType.CE, entryLtp, new BigDecimal("0.12")),
            new BigDecimal("0.55")),
        structuralStop);
  }

  private void seedTick(String exchange, String sym, String ltp) {
    try {
      redis.opsForHash().put(
          "ticks:last", exchange + ":" + sym,
          objectMapper.writeValueAsString(
              Map.of("exchange", exchange, "tradingsymbol", sym, "lastPrice", ltp)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void opensEligibleRejectionOnceAndSkipsIneligible() {
    UUID v = versionId();
    String slug = "shadow-it-open-" + UUID.randomUUID();
    String optSym = "SHDOPT" + UUID.randomUUID().toString().substring(0, 8);
    long r1 = rejection(v, slug, "SHDFUT1");

    Long id =
        shadowBook.maybeOpen(r1, v, slug, "NFO", "SHDFUT1", OffsetDateTime.now(),
            diag(optSym, new BigDecimal("0.70"), new BigDecimal("100.00"), null));
    assertThat(id).isNotNull();

    // dedup: a second eligible rejection for the SAME strategy+side while one is OPEN → skipped
    long r2 = rejection(v, slug, "SHDFUT1");
    assertThat(
            shadowBook.maybeOpen(r2, v, slug, "NFO", "SHDFUT1", OffsetDateTime.now(),
                diag(optSym, new BigDecimal("0.75"), new BigDecimal("101.00"), null)))
        .isNull();

    // composite below threshold → not the would-have-fired class → skipped
    long r3 = rejection(v, slug + "-b", "SHDFUT1");
    assertThat(
            shadowBook.maybeOpen(r3, v, slug + "-b", "NFO", "SHDFUT1", OffsetDateTime.now(),
                diag(optSym, new BigDecimal("0.40"), new BigDecimal("100.00"), null)))
        .isNull();

    var row = shadows.open().stream().filter(s -> s.strategySlug().equals(slug)).toList();
    assertThat(row).hasSize(1);
    assertThat(row.get(0).rejectionId()).isEqualTo(r1);
    assertThat(row.get(0).exchange()).isEqualTo("NFO");
    assertThat(row.get(0).entryLtp()).isEqualByComparingTo("100.00");
    assertThat(row.get(0).blockingRail()).isEqualTo("volume-floor");
  }

  /**
   * The discriminating case (cross-vendor review Major 4). The shadow book stamps the SAME exchange
   * the real ENTRY would, so a guess here corrupts the counterfactual it exists to measure: the row
   * would be marked to a contract that does not trade on that exchange. The underlying is
   * {@code NIFTY 50}, so the removed {@code startsWith("SENSEX")…} helper returns NFO — only reading
   * the picked candidate's master-sourced value persists BFO.
   */
  @Test
  void persistsThePickedLegsMasterExchangeNotAGuessFromTheUnderlying() {
    UUID v = versionId();
    String slug = "shadow-it-exch-" + UUID.randomUUID();
    String optSym = "SHDOPT" + UUID.randomUUID().toString().substring(0, 8);
    long r = rejection(v, slug, "SHDFUT-EXCH");

    assertThat(
            shadowBook.maybeOpen(r, v, slug, "NFO", "SHDFUT-EXCH", OffsetDateTime.now(),
                diag(optSym, new BigDecimal("0.70"), new BigDecimal("100.00"), null, "BFO")))
        .isNotNull();

    var row = shadows.open().stream().filter(s -> s.strategySlug().equals(slug)).toList();
    assertThat(row).hasSize(1);
    assertThat(row.get(0).exchange())
        .as("a name-prefix guess on 'NIFTY 50' would have written NFO")
        .isEqualTo("BFO");
  }

  /**
   * An unkeyed leg opens NO shadow book. {@code shadow_positions.exchange} is NOT NULL (V016), so
   * inserting one would surface as a constraint violation swallowed by the caller's catch — a DB
   * error standing in for a knowable condition. A book that cannot be priced is not worth a row.
   */
  @Test
  void skipsTheBookEntirelyWhenTheLegHasNoMasterExchange() {
    UUID v = versionId();
    String slug = "shadow-it-noexch-" + UUID.randomUUID();
    String optSym = "SHDOPT" + UUID.randomUUID().toString().substring(0, 8);
    long r = rejection(v, slug, "SHDFUT-NOEXCH");

    assertThat(
            shadowBook.maybeOpen(r, v, slug, "NFO", "SHDFUT-NOEXCH", OffsetDateTime.now(),
                diag(optSym, new BigDecimal("0.70"), new BigDecimal("100.00"), null, null)))
        .isNull();
    assertThat(shadows.open().stream().filter(s -> s.strategySlug().equals(slug))).isEmpty();
  }

  @Test
  void monitorClosesOnTakeProfitAndStopLossBrackets() {
    UUID v = versionId();
    String tpSym = "SHDTP" + UUID.randomUUID().toString().substring(0, 8);
    String slSym = "SHDSL" + UUID.randomUUID().toString().substring(0, 8);
    long tpId =
        shadows.insert(
            rejection(v, "shadow-it-tp-" + tpSym, "SHDFUT2"), v, "shadow-it-tp-" + tpSym, "CE",
            "NIFTY 50", "NFO", tpSym, new BigDecimal("24250"), LocalDate.now().plusDays(5),
            new BigDecimal("100.00"), new BigDecimal("65.00"), new BigDecimal("135.00"), null,
            "NFO", "SHDFUT2", "volume-floor", new BigDecimal("0.70"), OffsetDateTime.now(), "champion");
    long slId =
        shadows.insert(
            rejection(v, "shadow-it-sl-" + slSym, "SHDFUT2"), v, "shadow-it-sl-" + slSym, "CE",
            "NIFTY 50", "NFO", slSym, new BigDecimal("24250"), LocalDate.now().plusDays(5),
            new BigDecimal("100.00"), new BigDecimal("65.00"), new BigDecimal("135.00"), null,
            "NFO", "SHDFUT2", "volume-floor", new BigDecimal("0.70"), OffsetDateTime.now(), "champion");
    seedTick("NFO", tpSym, "140.00");
    seedTick("NFO", slSym, "60.00");

    monitor.evaluate(LocalTime.of(11, 0), LocalDate.now(Ist.ZONE));

    var tp = find(tpId);
    assertThat(tp.status()).isEqualTo("CLOSED");
    assertThat(tp.closeReason()).isEqualTo("TAKE_PROFIT");
    assertThat(tp.exitLtp()).isEqualByComparingTo("140.00");
    assertThat(tp.pnlPoints()).isEqualByComparingTo("40.00");
    assertThat(tp.pnlPct()).isEqualByComparingTo("40.00");
    var sl = find(slId);
    assertThat(sl.closeReason()).isEqualTo("STOP_LOSS");
    assertThat(sl.pnlPoints()).isEqualByComparingTo("-40.00");
  }

  @Test
  void monitorClosesOnSignalFutureStructuralStop() {
    UUID v = versionId();
    String optSym = "SHDST" + UUID.randomUUID().toString().substring(0, 8);
    String futSym = "SHDSTFUT" + UUID.randomUUID().toString().substring(0, 6);
    long id =
        shadows.insert(
            rejection(v, "shadow-it-st-" + optSym, futSym), v, "shadow-it-st-" + optSym, "CE",
            "NIFTY 50", "NFO", optSym, new BigDecimal("24250"), LocalDate.now().plusDays(5),
            new BigDecimal("100.00"), null, null, new BigDecimal("24200"),
            "NFO", futSym, "rsi-band", new BigDecimal("0.70"), OffsetDateTime.now(), "champion");
    // the CE structural stop trips when the SIGNAL FUTURE trades at/below the level
    seedTick("NFO", futSym, "24150.00");
    seedTick("NFO", optSym, "88.00");

    monitor.evaluate(LocalTime.of(11, 0), LocalDate.now(Ist.ZONE));

    var row = find(id);
    assertThat(row.closeReason()).isEqualTo("STRUCTURAL_STOP");
    assertThat(row.exitLtp()).isEqualByComparingTo("88.00");
    assertThat(row.pnlPoints()).isEqualByComparingTo("-12.00");
  }

  @Test
  void monitorSquaresOffAfterCutoffAndStalesPriorDayRows() {
    UUID v = versionId();
    String soSym = "SHDSO" + UUID.randomUUID().toString().substring(0, 8);
    String staleSym = "SHDXX" + UUID.randomUUID().toString().substring(0, 8);
    long soId =
        shadows.insert(
            rejection(v, "shadow-it-so-" + soSym, "SHDFUT3"), v, "shadow-it-so-" + soSym, "CE",
            "NIFTY 50", "NFO", soSym, new BigDecimal("24250"), LocalDate.now().plusDays(5),
            new BigDecimal("100.00"), null, null, null,
            "NFO", "SHDFUT3", "volume-floor", new BigDecimal("0.70"), OffsetDateTime.now(), "champion");
    long staleId =
        shadows.insert(
            rejection(v, "shadow-it-stale-" + staleSym, "SHDFUT3"), v,
            "shadow-it-stale-" + staleSym, "CE", "NIFTY 50", "NFO", staleSym,
            new BigDecimal("24250"), LocalDate.now().plusDays(5), new BigDecimal("100.00"),
            null, null, null, "NFO", "SHDFUT3", "volume-floor", new BigDecimal("0.70"),
            OffsetDateTime.now(), "champion");
    jdbc.update(
        "UPDATE shadow_positions SET opened_at = opened_at - INTERVAL '1 day' WHERE id = ?", staleId);
    seedTick("NFO", soSym, "105.00");

    // before the square-off cutoff nothing closes
    monitor.evaluate(LocalTime.of(15, 11), LocalDate.now(Ist.ZONE));
    assertThat(find(soId).status()).isEqualTo("OPEN");

    monitor.evaluate(LocalTime.of(15, 13), LocalDate.now(Ist.ZONE));

    var so = find(soId);
    assertThat(so.closeReason()).isEqualTo("SQUARE_OFF");
    assertThat(so.pnlPoints()).isEqualByComparingTo("5.00");
    var stale = find(staleId);
    assertThat(stale.status()).isEqualTo("CLOSED");
    assertThat(stale.closeReason()).isEqualTo("STALE");
    assertThat(stale.exitLtp()).isNull();
    assertThat(stale.pnlPoints()).isNull();
  }

  private ShadowPositionRepository.ShadowRow find(long id) {
    return jdbc.queryForObject(
        "SELECT status, close_reason, exit_ltp, pnl_points, pnl_pct FROM shadow_positions WHERE id = ?",
        (rs, i) ->
            new ShadowPositionRepository.ShadowRow(
                id, 0, null, null, null, null, null, null, null, null, BigDecimal.ONE, null, null,
                null, null, null, null, null, null, null, rs.getString("status"),
                rs.getBigDecimal("exit_ltp"), rs.getString("close_reason"), null,
                rs.getBigDecimal("pnl_points"), rs.getBigDecimal("pnl_pct"), "champion", null, null),
        id);
  }
}
