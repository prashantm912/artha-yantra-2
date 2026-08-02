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
 * ticker). The fix is IN MEMORY ONLY ({@link ManasGoverningStopCache}), never a database write:
 * {@code cacheManasGoverningStopTightensButNeverLoosens} pins the cache mechanics directly;
 * {@code cachingAnExistingPositionsStopFlipsAFreshEntryFromRefusedToAdmitted} proves the SAME
 * candidate is refused before a cache write and admitted after one; and {@code
 * theCacheWriteNeverTouchesStopLossTheIntradayDisasterStop} is THE load-bearing proof the owner
 * specifically asked for — a FULL admission cycle that reads the cache must NEVER write {@code
 * stop_loss}. {@code ManasAroraSwingEngineTest#anArmedTrailRatchetsTheGoverningStopWithoutFiringAnExit}
 * covers the OTHER half: that {@code SwingBatchEngine}'s daily exit pass is what populates the cache.
 *
 * <p><b>Round 4 (cross-vendor review, 2026-08-02) adds three fixes' end-to-end proofs.</b> Critical
 * 1: the cache is now keyed by the position's OWN id (round 3 keyed it by the compound
 * (book,exchange,symbol,side) tuple, letting a stale trail attach to a DIFFERENT position sharing
 * that key — a dead-anchor row misclassified "fresh" by the entry pass, or a manual close racing
 * the daily batch's exit pass). {@code anAveragingAddEvictsTheStaleCachedGoverningStopSoALaterCandidateIsCorrectlyRefused}
 * proves an averaging add (same id, qty/avg change in place) evicts its own stale cache entry;
 * {@code aCacheWriteForAClosedPositionIsANoOpNotAResurrection} proves {@link
 * PaperEmissionGuard#cacheManasGoverningStop} resolves the CURRENT open row at write time, so a
 * write racing a close can never resurrect a stale entry, and a later reopen (a NEW id) starts with
 * no inherited cache entry. Critical 2: {@code
 * aZeroEquityManasBookRefusesRatherThanSilentlyAdmitting} proves the safety gate now fails CLOSED
 * (refuses) rather than silently admitting when it cannot compute a risk figure.
 *
 * <p><b>Round 5 (cross-vendor review, 2026-08-02): round 4 fixed the READ side of position
 * identity, not the WRITE side.</b> Resolving "whichever row is currently open" (round 4) stops a
 * NEW position from INHERITING a stale entry, but does not stop the write from CREATING a
 * wrongly-attributed one: if the anchor whose trail was computed closes and a DIFFERENT anchor's
 * position takes the same key before the write lands, "whatever is open" is the wrong position.
 * {@link PaperEmissionGuard#cacheManasGoverningStop} now takes the anchor's own
 * {@code openingSignalId} and validates it against the currently-open row's {@code
 * opening_signal_id} column ({@link PaperPositionRepository#findOpenIdIfOpenedBy}) before caching.
 * This class's fixture has no real signal-anchor chain, so the anchor-MISMATCH-while-still-OPEN
 * proof lives in {@code
 * SwingPaperExitCriticalsIntegrationTest.aStaleAnchorsGoverningStopIsNeverAttachedToADifferentPositionOnTheSameKey},
 * which does; {@code aCacheWriteForAClosedPositionIsANoOpNotAResurrection} here still covers the
 * simpler "nothing is OPEN at all" case correctly regardless of which {@code openingSignalId} is
 * passed. Round 5 also closed the SECOND half of Critical 2: {@link
 * PaperEmissionGuard#openRiskInr} silently skips a stopless row (and its BUY-only arithmetic zeros
 * a SHORT's real risk) when summing the WHOLE book, not just the candidate's own matching row —
 * {@code manasAggregateRiskCheck} now sweeps every open row and refuses on either shape, LATENT
 * today (measured 2026-08-02: manas-arora's 6/6 open rows all carry a stop, zero open SELL rows
 * exist in any book) but no longer fail-open on principle.
 *
 * <p><b>Round 6 fixed the SELL-admits-at-zero-risk gap; round 7 (owner-approved, 2026-08-02) fixed
 * what round 6 broke.</b> Round 6's one-line guard refused an incoming non-BUY candidate — but it
 * returned the SAME {@code true} a genuinely calculated breach returns, so {@code
 * PaperService#openOrder} audited it via {@link RiskService#recordPyramidRiskCapBreach}, which
 * consumes the ONE-PER-IST-DAY-PER-BOOK dedup key. An accidental manual SELL would therefore
 * silently SUPPRESS the audit/alert for a LATER, GENUINE breach the SAME day. {@link
 * RiskService#manasAggregateRiskCheck} now returns a TYPED {@link RiskService.ManasRiskOutcome} —
 * only {@link RiskService.ManasRiskOutcome#CALCULATED_BREACH} may reach {@code
 * recordPyramidRiskCapBreach}; every cannot-calculate refusal (unsupported side, undefined
 * governing stop, non-positive equity) gets the SEPARATE {@code MANAS_RISK_UNCOMPUTABLE} rail and
 * never touches the audit/dedup. {@code
 * aFreshSellRefusesWithoutClaimingACalculatedBreachOrConsumingTheDedupKey} proves the refusal
 * itself and the missing audit row; {@code
 * aGenuineBreachStillAuditsAfterAnEarlierUnsupportedSideRefusalTheSameDay} is THE assertion this
 * Critical is about — a genuine breach the SAME book, SAME day, AFTER an unrelated SELL refusal,
 * still audits exactly once.
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
  @Autowired private PaperEmissionGuard emissionGuard;
  @Autowired private PaperPositionRepository positions;
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
    // The governing-stop cache is ALSO a shared in-memory Spring bean across test methods, but round
    // 4 keys it by POSITION ID, not the symbol tuple — every insertOpen() below mints a fresh
    // auto-increment id, so a prior test method's entries can never collide with (or be read by) a
    // later one. No explicit cross-method eviction is needed anymore (round 3 needed it; this is the
    // simplification the id-keyed design buys).
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
        symbol, side, new BigDecimal(qty), new BigDecimal(avgEntry),
        stop == null ? null : new BigDecimal(stop), BOOK);
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

  /** The row id of the (assumed unique-per-test) currently OPEN position for this symbol. */
  private long positionId(String symbol) {
    return jdbc.queryForObject(
        "SELECT id FROM paper_positions WHERE book=? AND tradingsymbol=? AND status='OPEN'",
        Long.class, BOOK, symbol);
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
    // the tighten-only guard, independent of anything SwingBatchEngine computes. Pure cache API, so a
    // synthetic id (no DB row needed) is enough — round 4 keys by id instead of a symbol tuple.
    long id = 424242L;
    governingStopCache.put(id, "BUY", new BigDecimal("95.00"));
    assertThat(governingStopCache.get(id)).isEqualByComparingTo("95.00");

    governingStopCache.put(id, "BUY", new BigDecimal("92.00"));
    assertThat(governingStopCache.get(id))
        .as("a LOOSER value is rejected in the cache itself, not merely by caller discipline")
        .isEqualByComparingTo("95.00");

    governingStopCache.put(id, "BUY", new BigDecimal("97.00"));
    assertThat(governingStopCache.get(id)).isEqualByComparingTo("97.00");
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
    governingStopCache.put(positionId("RATCHETCO"), "BUY", new BigDecimal("97.50")); // as if armed
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
    // only for a LONG. Manas trades long-only by doctrine; this guard is LATENT, not a response to
    // a live row (measured 2026-08-02: zero open SELL rows in any book today) — it must still
    // REJECT (never cache) rather than silently applying a higher-is-tighter comparison that would
    // be backwards for a short if one were ever opened. Pure cache API — synthetic id.
    long id = 777777L;
    governingStopCache.put(id, "SELL", new BigDecimal("105.00"));

    assertThat(governingStopCache.get(id))
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
    long ratchetcoId = positionId("RATCHETCO");
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

    governingStopCache.put(ratchetcoId, "BUY", new BigDecimal("95.00"));
    assertThat(governingStopCache.get(ratchetcoId)).isEqualByComparingTo("95.00");
    assertThat(currentStop("RATCHETCO")).as("stop_loss is untouched").isEqualByComparingTo("91.30");

    PaperService.PositionDto opened = paper.openOrder(candidate);
    assertThat(opened.status())
        .as("AFTER the cache write: 5,000 existing + 500 new = 5,500 = 3.67% — the SAME candidate admits")
        .isEqualTo("OPEN");
  }

  @Test
  void anAveragingAddEvictsTheStaleCachedGoverningStopSoALaterCandidateIsCorrectlyRefused() {
    // Round 4, cross-vendor review Critical 1: an averaging add keeps the SAME position id (qty/avg
    // change in place), but the CACHED trail was computed against the PRE-average avg/qty and must
    // not silently keep governing the blended position. RATCHETCO 1000@100/stop_loss=91.30, cached
    // (armed) at 97.50 -> effective risk 1000*(100-97.5)=2,500 (1.67%). An averaging add of 10@100
    // (same price, so the average stays exactly 100) is admitted under the CACHED figure
    // (1010*2.5=2,525=1.68%) and must EVICT the cache entry as a side effect.
    insertOpen("RATCHETCO", "1000", "100", "91.30");
    long ratchetcoId = positionId("RATCHETCO");
    governingStopCache.put(ratchetcoId, "BUY", new BigDecimal("97.50"));

    PaperService.PositionDto averaged =
        paper.openOrder(
            new PaperService.OrderRequest(
                null, "NSE", "RATCHETCO", "BUY", 10, new BigDecimal("100.00"), new BigDecimal("999.00"),
                null, null, BOOK));
    assertThat(averaged.status()).isEqualTo("OPEN");
    assertThat(governingStopCache.get(ratchetcoId))
        .as("the average evicted the stale cache entry for this id")
        .isNull();

    // Post-average: 1010@100, stop_loss STILL 91.30 (never touched) -> reverted risk =
    // 1010*(100-91.30)=8,787 (5.858%). A THIRDCO candidate of 50@100/stop90 (risk 500) totals
    // 8,787+500=9,287=6.19% -> BREACHES the 6% cap. Had the stale cache (97.50) survived the
    // average, the SAME candidate would have totaled 2,525+500=3,025=2.02% -> wrongly ADMITTED.
    assertThatThrownBy(
            () ->
                paper.openOrder(
                    new PaperService.OrderRequest(
                        null, "NSE", "THIRDCO-" + UUID.randomUUID(), "BUY", 50,
                        new BigDecimal("100.00"), new BigDecimal("90.00"), null, null, BOOK)))
        .as("the reverted (conservative) risk for the averaged RATCHETCO position correctly breaches"
            + " the cap — proving the stale cache entry no longer governs")
        .isInstanceOf(ApiException.class)
        .hasMessageContaining(RiskService.PYRAMID_RISK_CAP);
  }

  @Test
  void aCacheWriteForAClosedPositionIsANoOpNotAResurrection() {
    // Round 4, cross-vendor review Critical 1 (the close-race half): SwingBatchEngine's exit pass has
    // no @Transactional boundary of its own, so PaperEmissionGuard#cacheManasGoverningStop resolves
    // the CURRENTLY open row via a fresh read at write time — never a stale in-loop snapshot. If the
    // position already closed (via the REAL closer, positions.close — the sole production writer of
    // this transition, standing in for a manual close that raced the batch: it stamps closed_at
    // atomically with status, satisfying ck_paper_positions_closed_at_matches_status, unlike a raw
    // status-only UPDATE which CI caught as a state production cannot produce), the fresh lookup
    // finds nothing OPEN and the write is a safe no-op: it can never resurrect a stale entry under a
    // symbol/side key that no longer has an open row.
    // Round 5 additionally validates the anchor identity (opening_signal_id) once something IS open
    // — this row's fixture never sets that column, so ANY openingSignalId value correctly finds no
    // match here regardless (status='OPEN' fails first); the anchor-mismatch-while-still-OPEN case
    // is covered separately by
    // SwingPaperExitCriticalsIntegrationTest.aStaleAnchorsGoverningStopIsNeverAttachedToADifferentPositionOnTheSameKey,
    // which has the real signal-anchor fixture this class does not.
    insertOpen("RATCHETCO", "1000", "100", "91.30");
    long closedId = positionId("RATCHETCO");
    assertThat(positions.close(closedId, BigDecimal.ZERO, "MANUAL")).isEqualTo(1);

    emissionGuard.cacheManasGoverningStop(
        BOOK, "NSE", "RATCHETCO", "BUY", 999_999L, new BigDecimal("97.50"));

    assertThat(governingStopCache.get(closedId))
        .as("nothing was OPEN for this key at write time — the write is a no-op, not a resurrection")
        .isNull();

    // A later, genuinely NEW position on the same symbol/side (a real reopen) gets a DIFFERENT id and
    // starts with no inherited cache entry — its risk falls back to its OWN stop_loss, never the
    // closed position's would-be 97.50.
    insertOpen("RATCHETCO", "500", "100", "80.00");
    long reopenedId = positionId("RATCHETCO");
    assertThat(reopenedId).isNotEqualTo(closedId);
    assertThat(governingStopCache.get(reopenedId))
        .as("the new position's id has no cache entry — never inherited from the closed one")
        .isNull();
  }

  @Test
  void aZeroEquityManasBookRefusesRatherThanSilentlyAdmitting() {
    // Round 4, cross-vendor review Critical 2: a safety gate must fail CLOSED, not open, when it
    // cannot compute a percentage of equity. Zero out the book's equity (no open positions, so cash
    // + realized P&L is the whole of it) and confirm even an ordinarily-tiny fresh fill is refused.
    // Round 7: this is a CANNOT-CALCULATE refusal (MANAS_RISK_UNCOMPUTABLE), never a calculated
    // breach — it must not touch the PYRAMID_RISK_CAP audit trail at all.
    jdbc.update("UPDATE paper_account SET starting_capital=0, cash=0 WHERE book=?", BOOK);
    String sym = "ZEROEQ-" + UUID.randomUUID();

    assertThatThrownBy(
            () ->
                paper.openOrder(
                    new PaperService.OrderRequest(
                        null, "NSE", sym, "BUY", 1, new BigDecimal("100.00"),
                        new BigDecimal("99.00"), null, null, BOOK)))
        .as("zero equity means the risk-cap percentage is undefined — refuse, never silently admit,"
            + " and never claim a calculated 6% breach")
        .isInstanceOf(ApiException.class)
        .hasMessageContaining(RiskService.MANAS_RISK_UNCOMPUTABLE);
    assertThat(openCount(sym)).isZero();
    assertThat(pyramidRiskCapTrips())
        .as("a cannot-calculate refusal must NEVER write a PYRAMID_RISK_CAP audit trip")
        .isZero();
  }

  @Test
  void aStoplessUnrelatedOpenPositionRefusesEvenATinyFreshCandidateAtTheWriter() {
    // Round 5, cross-vendor review Critical 2 (the "real half"): round 4 only validated the
    // CANDIDATE's own matching row; the aggregate SUM over every OTHER open row in the book still
    // silently skipped a stopless one. OLDCO (unrelated symbol, no stop_loss at all, no cache entry)
    // must make the WHOLE book's aggregate untrustworthy — even a tiny, well-stopped fresh candidate
    // on a different symbol is refused at the real writer. LATENT today (measured 2026-08-02:
    // manas-arora's 6/6 open rows all carry a stop) — this proves the wiring end-to-end regardless.
    // Round 7: this is a CANNOT-CALCULATE refusal, never a calculated breach.
    insertOpen("OLDCO", "10", "100", null);
    String sym = "TINYFRESH-" + UUID.randomUUID();

    assertThatThrownBy(
            () ->
                paper.openOrder(
                    new PaperService.OrderRequest(
                        null, "NSE", sym, "BUY", 1, new BigDecimal("100.00"),
                        new BigDecimal("99.00"), null, null, BOOK)))
        .as("OLDCO's undefined risk makes the whole book's aggregate untrustworthy — refuse, but"
            + " never claim a calculated 6% breach")
        .isInstanceOf(ApiException.class)
        .hasMessageContaining(RiskService.MANAS_RISK_UNCOMPUTABLE);
    assertThat(openCount(sym)).isZero();
    assertThat(pyramidRiskCapTrips())
        .as("a cannot-calculate refusal must NEVER write a PYRAMID_RISK_CAP audit trip")
        .isZero();
  }

  private int pyramidRiskCapTrips() {
    Integer trips =
        jdbc.queryForObject(
            "SELECT count(*) FROM risk_audit WHERE book=? AND action='TRIP' AND key=?",
            Integer.class, BOOK, RiskService.PYRAMID_RISK_CAP);
    return trips == null ? 0 : trips;
  }

  /**
   * Round 7 (owner-approved, 2026-08-02) — the fix for the Critical round 6's one-liner caused: a
   * fresh SELL is a CANNOT-CALCULATE refusal ({@link RiskService.ManasRiskOutcome#UNSUPPORTED_SIDE}),
   * not a calculated breach, so it must refuse WITHOUT writing a {@code PYRAMID_RISK_CAP} audit trip
   * and WITHOUT consuming that rail's per-day dedup key.
   */
  @Test
  void aFreshSellRefusesWithoutClaimingACalculatedBreachOrConsumingTheDedupKey() {
    String sym = "SELLNOAUDIT-" + UUID.randomUUID();

    assertThatThrownBy(
            () ->
                paper.openOrder(
                    new PaperService.OrderRequest(
                        null, "NSE", sym, "SELL", 100, new BigDecimal("100.00"),
                        new BigDecimal("110.00"), null, null, BOOK)))
        .as("round 6 regression guard: a fresh SELL must still be refused, not admitted")
        .isInstanceOf(ApiException.class)
        .hasMessageContaining(RiskService.MANAS_RISK_UNCOMPUTABLE);
    assertThat(openCount(sym)).isZero();
    assertThat(pyramidRiskCapTrips())
        .as("the SELL refusal must NOT write a PYRAMID_RISK_CAP audit trip — it is not a calculated"
            + " breach")
        .isZero();
  }

  /**
   * Round 7 (owner-approved, 2026-08-02) — THE assertion that matters most. Round 6's one-liner
   * fixed the SELL-admits-at-zero-risk bug but reused the SAME {@code true}/refuse path a genuine
   * calculated breach uses, so {@code recordPyramidRiskCapBreach} consumed the ONE-PER-IST-DAY
   * dedup key for an accidental SELL refusal — silently SUPPRESSING the audit/alert for a LATER,
   * GENUINE breach on the SAME book the SAME day. Proves the suppression is gone: an unsupported-
   * side refusal followed by a real >6% breach on the SAME book, SAME day, STILL audits/alerts.
   */
  @Test
  void aGenuineBreachStillAuditsAfterAnEarlierUnsupportedSideRefusalTheSameDay() {
    // Step 1: an unsupported-side (SELL) refusal — cannot-calculate, must not touch the dedup key.
    assertThatThrownBy(
            () ->
                paper.openOrder(
                    new PaperService.OrderRequest(
                        null, "NSE", "SELLFIRST-" + UUID.randomUUID(), "SELL", 100,
                        new BigDecimal("100.00"), new BigDecimal("110.00"), null, null, BOOK)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining(RiskService.MANAS_RISK_UNCOMPUTABLE);
    assertThat(pyramidRiskCapTrips())
        .as("the SELL refusal alone must not have tripped the pyramid-risk-cap audit yet")
        .isZero();

    // Step 2: a GENUINE calculated breach, same book, same IST day (mock CLOCK is real system time
    // in this @SpringBootTest, so both calls land the same IST calendar day within the test run).
    // Existing 100@100/stop13 risks 8,700 (5.8%); a fresh 50@100/stop90 candidate adds 500 -> 9,200
    // = 6.13% -> a genuine breach of the 6% cap, well clear of slippage noise.
    insertOpen("GENUINE", "100", "100", "13");
    String sym = "GENUINEBREACH-" + UUID.randomUUID();

    assertThatThrownBy(
            () ->
                paper.openOrder(
                    new PaperService.OrderRequest(
                        null, "NSE", sym, "BUY", 50, new BigDecimal("100.00"),
                        new BigDecimal("90.00"), null, null, BOOK)))
        .as("a genuine calculated breach must still refuse")
        .isInstanceOf(ApiException.class)
        .hasMessageContaining(RiskService.PYRAMID_RISK_CAP);
    assertThat(openCount(sym)).isZero();
    // A bare COUNT of 1 is NOT sufficient here: the historical round-6 bug also lands at exactly 1
    // trip (step 1's SELL refusal mislabeled as a breach, then step 2 silently deduped) — the
    // discriminator is WHICH fill the recorded trip's detail describes.
    String latestTripDetail =
        jdbc.queryForObject(
            "SELECT detail FROM risk_audit WHERE book=? AND action='TRIP' AND key=?"
                + " ORDER BY id DESC LIMIT 1",
            String.class, BOOK, RiskService.PYRAMID_RISK_CAP);
    assertThat(pyramidRiskCapTrips())
        .as("exactly 1 trip exists for today")
        .isEqualTo(1);
    assertThat(latestTripDetail)
        .as("THE assertion this Critical is about: the recorded trip must be STEP 2's genuine"
            + " breach (names its own symbol), not a leftover from step 1's SELL refusal consuming"
            + " the same-day dedup key and silently suppressing step 2's real audit/alert")
        .contains(sym);
  }
}
