package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M40 cross-vendor review Critical 1+2 fix (2026-08-02): end-to-end proof that {@code
 * PaperService#openOrder} — the sole authoritative writer — refuses a Manas fill that would breach
 * the aggregate open-risk cap, through the REAL money path (not the mocked {@link RiskService} unit
 * seam {@code RiskServiceManasAggregateRiskTest} covers, which pins the PRECISE slippage-crossing and
 * averaging arithmetic; this class proves the wiring: the writer actually calls it, actually throws,
 * actually audits, actually opens nothing). Mirrors {@code
 * PaperManualOrderGovernorIntegrationTest#theOrderThatCrossesTheDeploymentCapIsRefusedAtTheWriter}'s
 * shape for the deployment rail. Margins are deliberately WIDE (not hair's-breadth) so the assertions
 * do not depend on the exact slippage bps or instrument resolution — those are pinned precisely by
 * the pure-math test instead. Uses the 'manas-arora' book (V021-seeded, ₹1.5 L) and cleans only that
 * book's state so it never clobbers a sibling IT sharing the singleton DB.
 *
 * <p>Also carries the M40 Critical 3 fix's proof, ROUND 3 (owner ruling, 2026-08-02 — supersedes
 * round 1's {@code stop_loss} write and round 2's dedicated column, both reverted after the audit
 * found round 1 would have silently converted Manas from EOD-managed to intraday-trail-managed the
 * moment a live tick reached a held symbol, reachable even today via the mock profile's fixture
 * ticker). The fix is now IN MEMORY ONLY ({@link ManasGoverningStopCache}), never a database write:
 * {@code cacheManasGoverningStopTightensButNeverLoosens} pins the cache mechanics directly;
 * {@code cachingAnExistingPositionsStopFlipsAFreshEntryFromRefusedToAdmitted} proves the SAME
 * candidate is refused before a cache write and admitted after one — not merely "the cap still
 * works", but that the cached-stop change is what moved the outcome; and {@code
 * theCacheWriteNeverTouchesStopLossTheIntradayDisasterStop} is THE load-bearing proof the owner
 * specifically asked for — running a FULL admission cycle that reads the cache must NEVER write
 * {@code stop_loss}, the column {@code PaperBracketEvaluator} polls every 15 seconds with no book
 * filter, so its outcome stays byte-identical before and after this PR. {@code
 * ManasAroraSwingEngineTest#anArmedTrailRatchetsTheGoverningStopWithoutFiringAnExit} covers the
 * OTHER half: that {@code SwingBatchEngine}'s daily exit pass is what populates the cache, with the
 * real ATR/Chandelier arithmetic, when a held position does not exit.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class PaperServiceManasAggregateRiskIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final String BOOK = "manas-arora";

  @TestConfiguration
  static class StubInstruments {
    @Bean
    @Primary
    InstrumentMetaClient stubInstrumentMetaClient() {
      // Every test symbol resolves as a plain NSE equity (lot 1) — deterministic 5bps slippage
      // fallback, no live market-data instrument-master dependency.
      return (exchange, tradingsymbol) -> new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);
    }
  }

  @Autowired private PaperService paper;
  @Autowired private RiskService risk;
  @Autowired private ManasGoverningStopCache governingStopCache;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  @AfterEach
  void reset() {
    jdbc.update("DELETE FROM paper_positions WHERE book=?", BOOK);
    jdbc.update("DELETE FROM paper_orders WHERE book=?", BOOK);
    jdbc.update("DELETE FROM paper_order_rejections WHERE book=?", BOOK);
    jdbc.update("DELETE FROM risk_audit WHERE book=? AND key=?", BOOK, RiskService.PYRAMID_RISK_CAP);
    jdbc.update("UPDATE paper_account SET starting_capital=150000, cash=150000 WHERE book=?", BOOK);
    // PYRAMID_RISK_CAP is NOT a risk_settings row (its cap is the pyramid @Value knob, not a
    // DB-editable limit) — but RiskService's per-day trip dedup is an in-memory field on the shared
    // Spring bean this @SpringBootTest context caches across test METHODS, so a trip in one method
    // would otherwise silently suppress the audit row in the next (both land on the same IST "day").
    // update()'s trippedOn.remove(...) side effect is the only public way to re-arm that dedup;
    // the harmless-inert risk_settings row it also writes is not read by anything this rail consults.
    risk.update(BOOK, RiskService.PYRAMID_RISK_CAP, "{}");
    // The governing-stop cache is ALSO a shared in-memory Spring bean across test methods — evict
    // every symbol this class uses so one method's cached value can never leak into another's.
    for (String sym : new String[] {"RATCHETCO", "SHORTCO"}) {
      governingStopCache.evict(BOOK, "NSE", sym, "BUY");
      governingStopCache.evict(BOOK, "NSE", sym, "SELL");
    }
  }

  private void insertOpen(String symbol, String qty, String avgEntry, String stop) {
    insertOpen(symbol, "BUY", qty, avgEntry, stop);
  }

  private void insertOpen(String symbol, String side, String qty, String avgEntry, String stop) {
    jdbc.update(
        """
        INSERT INTO paper_positions
          (exchange, tradingsymbol, side, qty, avg_entry_price, stop_loss, status, opened_at, book)
        VALUES ('NSE', ?, ?, ?, ?, ?, 'OPEN', now(), ?)
        """,
        symbol, side, new BigDecimal(qty), new BigDecimal(avgEntry), new BigDecimal(stop), BOOK);
  }

  private int openCount(String symbol) {
    Integer c =
        jdbc.queryForObject(
            "SELECT count(*) FROM paper_positions WHERE book=? AND tradingsymbol=? AND status='OPEN'",
            Integer.class, BOOK, symbol);
    return c == null ? 0 : c;
  }

  /** The intraday disaster-stop — the column {@code PaperBracketEvaluator} polls every 15s. */
  private BigDecimal currentStop(String symbol) {
    return jdbc.queryForObject(
        "SELECT stop_loss FROM paper_positions WHERE book=? AND tradingsymbol=? AND status='OPEN'",
        BigDecimal.class, BOOK, symbol);
  }

  @Test
  void aFreshManasFillThatWouldBreachTheAggregateRiskCapIsRefusedAtTheWriterWithADurableAudit() {
    // Book equity ₹150,000, cap 6% (default, unconfigured knob) = ₹9,000. Existing 100@100/stop13
    // already risks 8,700 (5.8%). A fresh 50@100/stop90 candidate adds 500 -> 9,200 = 6.13%: over the
    // cap by a wide enough margin that a few bps of BUY slippage cannot flip the outcome either way —
    // this test is about the WIRING, the pure-math test pins the hair's-breadth slippage case.
    insertOpen("EXISTINGCO", "100", "100", "13");
    String sym = "MANASRISK-" + UUID.randomUUID();

    assertThatThrownBy(
            () ->
                paper.openOrder(
                    new PaperService.OrderRequest(
                        null, "NSE", sym, "BUY", 50, new BigDecimal("100.00"),
                        new BigDecimal("90.00"), null, null, BOOK)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining(RiskService.PYRAMID_RISK_CAP);

    assertThat(openCount(sym)).as("the refused fill opened no position").isZero();
    Integer trips =
        jdbc.queryForObject(
            "SELECT count(*) FROM risk_audit WHERE book=? AND action='TRIP' AND key=?",
            Integer.class, BOOK, RiskService.PYRAMID_RISK_CAP);
    assertThat(trips)
        .as("the refusal is durably audited (risk_audit), not just a thrown 422")
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void aManasFillThatStaysUnderTheAggregateRiskCapStillOpens() {
    // A small existing position (well under 6%) plus a modest new fill stays under the cap and must
    // still open — the discriminating counterpart proving the writer-level rail is not over-eager.
    insertOpen("EXISTINGCO", "10", "100", "95"); // 10*(100-95)=50 risk, trivial vs a 9,000 cap
    String sym = "MANASRISKOK-" + UUID.randomUUID();

    PaperService.PositionDto opened =
        paper.openOrder(
            new PaperService.OrderRequest(
                null, "NSE", sym, "BUY", 10, new BigDecimal("100.00"), new BigDecimal("95.00"),
                null, null, BOOK));

    assertThat(opened.status()).isEqualTo("OPEN");
    assertThat(openCount(sym)).isEqualTo(1);
  }

  @Test
  void anAveragingAddOntoAnOpenManasKeyIsProjectedAgainstTheRetainedStopAtTheWriter() {
    // The Critical 2 arithmetic, exercised through the REAL upsertPosition averaging path: existing
    // 100@100/stop50 (risk 5,000). A same-key fill at 200 (qty 100, requested stop 190 — never used,
    // the row's stop is RETAINED) averages to 200@150, retained stop 50 -> TRUE risk 200*(150-50)=
    // 20,000 = 13.33% of 150,000 — a clear breach. A NAIVE per-leg sum (existing 5,000 + this fill's
    // own 100*(200-190)=1,000 = 6,000 = 4%) would have wrongly ADMITTED it. The gap (4% vs 13.33%) is
    // wide enough that the assertion is robust to a few bps of slippage on either leg.
    insertOpen("AVGCO", "100", "100", "50");

    assertThatThrownBy(
            () ->
                paper.openOrder(
                    new PaperService.OrderRequest(
                        null, "NSE", "AVGCO", "BUY", 100, new BigDecimal("200.00"),
                        new BigDecimal("190.00"), null, null, BOOK)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining(RiskService.PYRAMID_RISK_CAP);

    // the refused add left the original position untouched at its first-open quantity.
    Long qty =
        jdbc.queryForObject(
            "SELECT qty FROM paper_positions WHERE book=? AND tradingsymbol=? AND status='OPEN'",
            Long.class, BOOK, "AVGCO");
    assertThat(qty).isEqualTo(100L);
  }

  @Test
  void cacheManasGoverningStopTightensButNeverLoosens() {
    // M40 Critical 3 fix, round 3 (2026-08-02): proves the IN-MEMORY cache's mechanics directly —
    // the tighten-only guard, independent of anything SwingBatchEngine computes.
    governingStopCache.put(BOOK, "NSE", "RATCHETCO", "BUY", new BigDecimal("95.00"));
    assertThat(governingStopCache.get(BOOK, "NSE", "RATCHETCO", "BUY")).isEqualByComparingTo("95.00");

    governingStopCache.put(BOOK, "NSE", "RATCHETCO", "BUY", new BigDecimal("92.00"));
    assertThat(governingStopCache.get(BOOK, "NSE", "RATCHETCO", "BUY"))
        .as("a LOOSER value is rejected in the cache itself, not merely by caller discipline")
        .isEqualByComparingTo("95.00");

    governingStopCache.put(BOOK, "NSE", "RATCHETCO", "BUY", new BigDecimal("97.00"));
    assertThat(governingStopCache.get(BOOK, "NSE", "RATCHETCO", "BUY")).isEqualByComparingTo("97.00");
  }

  @Test
  void theCacheWriteNeverTouchesStopLossTheIntradayDisasterStop() {
    // THE load-bearing proof the owner asked for (2026-08-02): a FULL admission cycle that reads the
    // governing-stop cache must NEVER write stop_loss, the column PaperBracketEvaluator polls every
    // 15 seconds with no book filter. Two earlier drafts of this fix (a direct stop_loss ratchet, then
    // a dedicated persisted column) were reverted specifically because they could have moved that
    // column; this in-memory design cannot, by construction — but this test proves it end-to-end
    // rather than resting on "the code has no SQL for it".
    insertOpen("RATCHETCO", "1000", "100", "91.30"); // 1000*(100-91.30)=8,700 = 5.8% of 150,000
    governingStopCache.put(BOOK, "NSE", "RATCHETCO", "BUY", new BigDecimal("97.50")); // as if armed
    assertThat(currentStop("RATCHETCO")).as("the initial intraday bracket, pre-cycle").isEqualByComparingTo("91.30");

    // Run a FULL admission cycle: a fresh candidate whose risk check reads the cached governing stop
    // for RATCHETCO (existing risk now 1000*(100-97.50)=2,500=1.67%) and the DB for its own fill.
    // Cap 6% = 9,000; candidate 50@100/stop90 adds 500 -> 3,000 = 2.0% -> ADMITTED (exercises the
    // cache read on the REAL money-write path, not a mocked one).
    PaperService.PositionDto opened =
        paper.openOrder(
            new PaperService.OrderRequest(
                null, "NSE", "FRESHCO-" + UUID.randomUUID(), "BUY", 50, new BigDecimal("100.00"),
                new BigDecimal("90.00"), null, null, BOOK));
    assertThat(opened.status()).isEqualTo("OPEN");

    assertThat(currentStop("RATCHETCO"))
        .as("stop_loss — the intraday disaster-stop — is BYTE-IDENTICAL to before the admission cycle")
        .isEqualByComparingTo("91.30");
  }

  @Test
  void theCacheRejectsANonLongSideRatherThanApplyingABackwardsComparison() {
    // SEPARATE, SMALLER finding (Architect audit, 2026-08-02): "tighter means higher" is correct
    // only for a LONG. Manas trades long-only in production, but a known parked SELL row exists in
    // the live manas-arora book, so this must REJECT (never cache) rather than silently applying a
    // higher-is-tighter comparison that would be backwards for a short.
    governingStopCache.put(BOOK, "NSE", "SHORTCO", "SELL", new BigDecimal("105.00"));

    assertThat(governingStopCache.get(BOOK, "NSE", "SHORTCO", "SELL"))
        .as("a non-BUY side is rejected outright, never cached")
        .isNull();
  }

  @Test
  void cachingAnExistingPositionsStopFlipsAFreshEntryFromRefusedToAdmitted() {
    // M40 Critical 3 fix, round 3 (2026-08-02), the "lowers computed aggregate risk" proof: the SAME
    // candidate is refused BEFORE the cache write and admitted AFTER it — not merely "the cap still
    // works", but that the cached governing-stop change is what moved the outcome. Book equity
    // ₹150,000, cap 6% = ₹9,000. Existing 1000@100/stop_loss=91.30 (unarmed) risks 8,700 (5.8%). A
    // fresh 50@100/stop90 candidate adds 500 -> 9,200 = 6.13% -> BREACH. Caching the existing
    // position's GOVERNING stop (not stop_loss) at 95 drops its effective risk to
    // 1000×(100-95)=5,000 (3.33%); the SAME candidate then totals 5,500 = 3.67% -> ADMITTED — while
    // stop_loss itself never moves (proven separately above).
    insertOpen("RATCHETCO", "1000", "100", "91.30");
    String sym = "MANASRATCHET-" + UUID.randomUUID();
    PaperService.OrderRequest candidate =
        new PaperService.OrderRequest(
            null, "NSE", sym, "BUY", 50, new BigDecimal("100.00"), new BigDecimal("90.00"), null,
            null, BOOK);

    assertThatThrownBy(() -> paper.openOrder(candidate))
        .as("BEFORE the cache write: 8,700 existing + 500 new = 9,200 = 6.13% breaches the 6% cap")
        .isInstanceOf(ApiException.class)
        .hasMessageContaining(RiskService.PYRAMID_RISK_CAP);
    assertThat(openCount(sym)).isZero();

    governingStopCache.put(BOOK, "NSE", "RATCHETCO", "BUY", new BigDecimal("95.00"));
    assertThat(governingStopCache.get(BOOK, "NSE", "RATCHETCO", "BUY")).isEqualByComparingTo("95.00");
    assertThat(currentStop("RATCHETCO")).as("stop_loss is untouched").isEqualByComparingTo("91.30");

    PaperService.PositionDto opened = paper.openOrder(candidate);
    assertThat(opened.status())
        .as("AFTER the cache write: 5,000 existing + 500 new = 5,500 = 3.67% — the SAME candidate admits")
        .isEqualTo("OPEN");
  }
}
