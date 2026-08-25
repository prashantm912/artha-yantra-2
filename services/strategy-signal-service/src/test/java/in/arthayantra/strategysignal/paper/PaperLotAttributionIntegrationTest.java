package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * V057 per-signal lot tagging: a position built by TWO strategies firing on the same bar decomposes
 * back into both, instead of crediting only the one that happened to open it.
 *
 * <p>This reproduces the live shape measured on 2026-08-03, where all 10 closed scalper positions
 * were 50/50 blends of {@code scalp-golden-crossover-*} and {@code scalp-connect-the-dots-*} — both
 * firing on the same bar at the same price seconds apart, both averaging into one row, and
 * {@code opening_signal_id} crediting only the first. A {@code GROUP BY slug} over that column
 * reported the second strategy at n=0 while it had contributed half of every trade.
 *
 * <p>The instrument-meta lookup is stubbed (no market-data in this service's IT) and every fill
 * carries an explicit price, so nothing here depends on a live tick.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
@AutoConfigureMockMvc
class PaperLotAttributionIntegrationTest extends StrategySignalIntegrationTestBase {

  @TestConfiguration
  static class StubInstruments {
    @Bean
    @Primary
    InstrumentMetaClient stubInstrumentMetaClient() {
      return (exchange, tradingsymbol) ->
          tradingsymbol.startsWith("LOTOPT")
              // Lot 5, not 50: openOrder now REFUSES a non-lot-multiple fill (the alignment rule
              // moved to the writer), and this fixture's quantities (10/20/25/30/40/65) were
              // chosen years before that rule existed. 5 divides every one of them, so the
              // QUANTITIES and every asserted figure derived from them are untouched — only the
              // stub's declared lot moves. Live is unaffected: all 40 F&O paper positions are
              // lot-aligned today (computed 2026-08-25), which is why the rule is safe to enforce.
              ? new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 5)
              : new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private PaperService paper;
  @Autowired private PaperPositionRepository positions;
  @Autowired private PaperPositionLotRepository lots;
  @Autowired private PaperOrderRepository orders;
  @Autowired private JdbcTemplate jdbc;

  /**
   * THE CORE CASE. Two DIFFERENT strategies open the same key on the same bar; the second averages
   * into the first's position. Both must be attributable, at their real qty and their real share of
   * the realized P&amp;L.
   */
  @Test
  void aPositionBuiltByTwoStrategiesDecomposesIntoBoth() {
    String suffix = UUID.randomUUID().toString();
    String book = "lotbook-" + suffix.substring(0, 8);
    String sym = "LOTOPT-" + suffix;
    String slugA = "lot-golden-" + suffix;
    String slugB = "lot-dots-" + suffix;
    long signalA = seedSignal(slugA, suffix + "-a");
    long signalB = seedSignal(slugB, suffix + "-b");

    // Both fire at the SAME price, as the live pair does — so the position's avg_entry_price is that
    // price and each strategy's per-unit basis is identical. That is what makes the blend impossible
    // to separate from the position row alone, and it is exactly the case the lots must handle.
    paper.openOrder(order(book, sym, signalA, 65, "200.00"));
    paper.openOrder(order(book, sym, signalB, 65, "200.00"));

    PaperPositionRepository.PositionRow open =
        positions.findOpen(book, "NFO", sym, "BUY").orElseThrow();
    assertThat(open.qty())
        .as("the second open AVERAGES into the first's position — unchanged behaviour")
        .isEqualTo(130L);
    assertThat(
            jdbc.queryForObject(
                "SELECT opening_signal_id FROM paper_positions WHERE id = ?", Long.class, open.id()))
        .as("and the position still credits ONLY the first signal — the defect being instrumented")
        .isEqualTo(signalA);

    // Close it at a profit so there is a realized figure to split.
    paper.closePosition(open.id(), new BigDecimal("210.00"));
    BigDecimal realized = positions.find(open.id()).orElseThrow().realizedPnl();
    assertThat(realized).as("the close realizes a non-zero P&L to attribute").isNotNull();

    List<PaperPositionLotRepository.AttributionRow> rows = lots.attribution(book);
    assertThat(rows).as("BOTH strategies appear — this is the whole point").hasSize(2);
    assertThat(rows).extracting(PaperPositionLotRepository.AttributionRow::slug)
        .containsExactlyInAnyOrder(slugA, slugB);
    assertThat(rows).allSatisfy(
        r -> assertThat(r.closedQty()).as("each contributed exactly its own 65").isEqualTo(65L));

    BigDecimal shareA = shareOf(rows, slugA);
    BigDecimal shareB = shareOf(rows, slugB);
    assertThat(shareA)
        .as("equal qty at an equal price is an equal share of the realized P&L")
        .isEqualByComparingTo(shareB);
    assertThat(shareA.add(shareB))
        .as("and the shares reconstruct the position's realized P&L exactly — no leakage")
        .isEqualByComparingTo(realized);
  }

  /**
   * THE DISCRIMINATING CASE — unequal fill prices, which the equal-price test above cannot see.
   *
   * <p>Cross-vendor review Critical 1: the first cut allocated realized P&amp;L by QUANTITY ALONE, so
   * two equal lots entered at ₹100 and ₹120 and exited around ₹110 were reported IDENTICALLY when
   * one had made money and the other lost it. That erases exactly the entry-quality difference a
   * keep/cut verdict turns on, and every existing test filled both lots at the same price (the live
   * scalper shape), so the suite was green.
   *
   * <p>Fill-basis attribution credits each lot with its OWN entry: the cheaper entry must come out
   * strictly ahead, and the two must still sum to the position's realized P&amp;L.
   */
  @Test
  void aPositionWithUnequalFillPricesAttributesTheEntryEdge() {
    String suffix = UUID.randomUUID().toString();
    String book = "lotedge-" + suffix.substring(0, 8);
    String sym = "LOTOPT-" + suffix;
    String cheap = "edge-cheap-" + suffix;
    String dear = "edge-dear-" + suffix;
    long signalCheap = seedSignal(cheap, suffix + "-c");
    long signalDear = seedSignal(dear, suffix + "-d");

    // Same qty, DIFFERENT prices — the blended basis lands at 110.
    paper.openOrder(order(book, sym, signalCheap, 65, "100.00"));
    paper.openOrder(order(book, sym, signalDear, 65, "120.00"));

    PaperPositionRepository.PositionRow open =
        positions.findOpen(book, "NFO", sym, "BUY").orElseThrow();
    // Derived, not hardcoded: the fill simulator adds a 1-tick (0.05) buy slippage, so the fills are
    // 100.05 and 120.05 and the basis is 110.05, not 110.00. Reading it back keeps this test honest
    // about the real fills rather than about the prices requested.
    List<BigDecimal> fills =
        jdbc.queryForList(
            "SELECT fill_price FROM paper_position_lots WHERE book = ? ORDER BY fill_price",
            BigDecimal.class,
            book);
    assertThat(fills).as("two lots, at two different prices").hasSize(2);
    BigDecimal spread = fills.get(1).subtract(fills.get(0));
    assertThat(open.avgEntryPrice())
        .as("the two fills blend to ONE basis — which is exactly what hides the difference")
        .isEqualByComparingTo(
            fills.get(0).add(fills.get(1)).divide(new BigDecimal("2"), 4, RoundingMode.HALF_UP));

    paper.closePosition(open.id(), new BigDecimal("110.00"));
    BigDecimal realized = positions.find(open.id()).orElseThrow().realizedPnl();

    List<PaperPositionLotRepository.AttributionRow> rows = lots.attribution(book);
    BigDecimal cheapShare = shareOf(rows, cheap);
    BigDecimal dearShare = shareOf(rows, dear);

    assertThat(cheapShare)
        .as("the lot that entered 20 points cheaper must be attributed STRICTLY more")
        .isGreaterThan(dearShare);
    // Entry edge is (avg - fill) * qty = ±10 * 65 = ±650, and costs ride the pro-rata term.
    assertThat(cheapShare.subtract(dearShare))
        .as("and by exactly the entry edge: the 20.00 fill spread x 65 units = 1300")
        .isEqualByComparingTo(spread.multiply(new BigDecimal("65")));
    assertThat(cheapShare.add(dearShare))
        .as("while still reconstructing the position's realized P&L exactly")
        .isEqualByComparingTo(realized);
  }

  /**
   * UNEQUAL QUANTITIES — the case that exposes the ledger-rounding residual.
   *
   * <p>Cross-vendor review Critical, round 3. Fill-basis attribution was justified by
   * {@code Σ(A - f_i)q_i = 0}, which holds for the EXACT lot-weighted mean but NOT for the mean
   * {@code PaperService.upsertPosition} actually stores, which is rounded to 4 decimals. The
   * algebra was right; the ledger was not consulted.
   *
   * <p>The reviewer's counterexample is used verbatim because it is a REAL pyramid shape — 65 and
   * 130 are exactly the scalper quantities this feature exists to decompose. Their exact mean
   * (100.00666…) is NOT representable at 4dp, so the stored average is off by a rounding step and
   * the entry-edge terms sum to +₹0.0065 instead of zero.
   *
   * <p>⚠️ Every earlier test used EQUAL quantities, whose mean lands exactly on a representable
   * midpoint — where the residual is exactly zero and both the right and the wrong implementation
   * agree. That is the third time in this PR a tidy fixture hid a real defect, so this test asserts
   * the fixture is DISCRIMINATING (the stored basis really is rounded) before asserting the fix.
   */
  @Test
  void aPositionWithUnequalQuantitiesStillSumsToRealized() {
    String suffix = UUID.randomUUID().toString();
    String book = "lotround-" + suffix.substring(0, 8);
    String sym = "LOTOPT-" + suffix;
    String small = "round-small-" + suffix;
    String large = "round-large-" + suffix;
    long signalSmall = seedSignal(small, suffix + "-s");
    long signalLarge = seedSignal(large, suffix + "-l");

    // Unequal QUANTITIES and unequal PRICES — the reviewer's counterexample.
    paper.openOrder(order(book, sym, signalSmall, 65, "100.00"));
    paper.openOrder(order(book, sym, signalLarge, 130, "100.01"));

    PaperPositionRepository.PositionRow open =
        positions.findOpen(book, "NFO", sym, "BUY").orElseThrow();
    assertThat(open.qty()).isEqualTo(195L);

    // THE FIXTURE MUST DISCRIMINATE: prove the stored basis really is a ROUNDED mean, otherwise
    // this test would pass against the broken implementation too.
    List<java.util.Map<String, Object>> lotRows =
        jdbc.queryForList(
            "SELECT qty, fill_price FROM paper_position_lots WHERE book = ? ORDER BY qty", book);
    BigDecimal weighted = BigDecimal.ZERO;
    for (java.util.Map<String, Object> r : lotRows) {
      weighted =
          weighted.add(
              ((BigDecimal) r.get("fill_price"))
                  .multiply(new BigDecimal(String.valueOf(r.get("qty")))));
    }
    BigDecimal exactMean = weighted.divide(new BigDecimal("195"), 10, RoundingMode.HALF_UP);
    assertThat(open.avgEntryPrice())
        .as("the stored basis is the mean ROUNDED to 4dp, not the exact mean — the whole defect")
        .isNotEqualByComparingTo(exactMean);
    BigDecimal residual =
        open.avgEntryPrice().multiply(new BigDecimal("195")).subtract(weighted);
    assertThat(residual.abs())
        .as("so the entry-edge terms have a NON-ZERO sum to allocate — the fixture discriminates")
        .isGreaterThan(BigDecimal.ZERO);

    paper.closePosition(open.id(), new BigDecimal("101.00"));
    BigDecimal realized = positions.find(open.id()).orElseThrow().realizedPnl();

    List<PaperPositionLotRepository.AttributionRow> rows = lots.attribution(book);
    assertThat(rows).hasSize(2);
    BigDecimal sum = shareOf(rows, small).add(shareOf(rows, large));
    assertThat(sum)
        .as("the shares must sum EXACTLY to realized P&L — no rounding residual leaks into the book")
        .isEqualByComparingTo(realized);
  }

  /**
   * SAME-POSITION legacy-then-tagged: a tagged add onto an UNTAGGED position keeps its entry edge.
   *
   * <p>Cross-vendor review Critical, round 4 — the round-3 residual fix creating a new defect. The
   * correction subtracts {@code sum(edge)} over a position's lots, which is a rounding artifact ONLY
   * when those lots cover the whole position. On a partially tagged position it is GENUINE signal:
   * for a legacy untagged {@code 65 @ ₹100} plus one tagged {@code 65 @ ₹120}, the tagged lot's real
   * edge is {@code −₹650}, and being the only lot that entire amount was subtracted, collapsing the
   * row to a quantity-only {@code R/2}.
   *
   * <p>⚠️ <b>This must be the SAME position.</b> The coverage test below builds a legacy position
   * and a tagged one SEPARATELY, and is therefore structurally blind to this — a separate position
   * is precisely the value at which the bug vanishes. That is the sixth fixture in this PR unable to
   * see the defect beside it, so this test derives its precondition from raw state before asserting:
   * it proves coverage really is partial and the edge really is material.
   */
  @Test
  void aTaggedAddOntoAnUntaggedPositionKeepsItsEntryEdge() {
    String suffix = UUID.randomUUID().toString();
    String book = "lotlegacy-" + suffix.substring(0, 8);
    String sym = "LOTOPT-" + suffix;
    String slug = "legacy-add-" + suffix;
    long signal = seedSignal(slug, suffix);

    // The legacy half: written straight to paper_positions, so it has NO lot — exactly the shape of
    // all 45 positions that exist today.
    positions.insertOpen(book, "NFO", sym, "BUY", 65L, new BigDecimal("100.00"), null, null);
    // The tagged half: averages into that SAME position and writes one lot.
    paper.openOrder(order(book, sym, signal, 65, "120.00"));

    PaperPositionRepository.PositionRow open =
        positions.findOpen(book, "NFO", sym, "BUY").orElseThrow();
    assertThat(open.qty()).as("one position, both halves").isEqualTo(130L);

    // FIXTURE SELF-CHECK 1 — coverage really is PARTIAL (this is what the gate keys on).
    Long taggedQty =
        jdbc.queryForObject(
            "SELECT coalesce(sum(qty), 0) FROM paper_position_lots WHERE position_id = ?",
            Long.class,
            open.id());
    assertThat(taggedQty)
        .as("only the add is tagged — if this equalled 130 the gate would not be exercised")
        .isEqualTo(65L);

    // FIXTURE SELF-CHECK 2 — the edge is MATERIAL, so its destruction would be detectable.
    BigDecimal taggedFill =
        jdbc.queryForObject(
            "SELECT fill_price FROM paper_position_lots WHERE position_id = ?",
            BigDecimal.class,
            open.id());
    BigDecimal edge =
        open.avgEntryPrice().subtract(taggedFill).multiply(new BigDecimal("65"));
    assertThat(edge.abs())
        .as("the tagged lot entered materially worse than the blend — a real edge to preserve")
        .isGreaterThan(new BigDecimal("100"));

    paper.closePosition(open.id(), new BigDecimal("115.00"));
    BigDecimal realized = positions.find(open.id()).orElseThrow().realizedPnl();
    BigDecimal quantityOnlyShare =
        realized.multiply(new BigDecimal("65")).divide(new BigDecimal("130"), 4, RoundingMode.HALF_UP);

    BigDecimal attributed = shareOf(lots.attribution(book), slug);
    assertThat(attributed)
        .as("the tagged lot keeps its entry edge — NOT collapsed to a bare quantity share")
        .isNotEqualByComparingTo(quantityOnlyShare);
    assertThat(attributed.subtract(quantityOnlyShare))
        .as("and differs from that quantity share by exactly the preserved edge")
        .isEqualByComparingTo(edge);

    // The untagged half is not invented — coverage reports it as the missing remainder.
    PaperPositionLotRepository.Coverage coverage = lots.coverage(book);
    assertThat(coverage.closedQty()).as("the position's full size").isEqualTo(130L);
    assertThat(coverage.closedQtyTagged())
        .as("of which only the add is attributable — coverage does its job")
        .isEqualTo(65L);
  }

  /**
   * The negative that makes the positive meaningful: the OLD attribution — grouping by the
   * position's {@code opening_signal_id} — reports the second strategy at n=0 on the very same data
   * the lots decompose correctly. Without this the test above cannot show it fixed anything.
   */
  @Test
  void theOpeningSignalIdGroupingReportsTheSecondStrategyAtZero() {
    String suffix = UUID.randomUUID().toString();
    String book = "lotold-" + suffix.substring(0, 8);
    String sym = "LOTOPT-" + suffix;
    String slugA = "old-golden-" + suffix;
    String slugB = "old-dots-" + suffix;
    long signalA = seedSignal(slugA, suffix + "-a");
    long signalB = seedSignal(slugB, suffix + "-b");

    paper.openOrder(order(book, sym, signalA, 65, "200.00"));
    paper.openOrder(order(book, sym, signalB, 65, "200.00"));

    List<String> credited =
        jdbc.queryForList(
            """
            SELECT st.slug
              FROM paper_positions p
              JOIN signals s ON s.id = p.opening_signal_id
              JOIN strategy_versions sv ON sv.id = s.strategy_version_id
              JOIN strategies st ON st.id = sv.strategy_id
             WHERE p.book = ?
            """,
            String.class,
            book);
    assertThat(credited)
        .as("the pre-V057 grouping sees ONE strategy where two traded")
        .containsExactly(slugA);
    assertThat(credited)
        .as("and the second is invisible at n=0 — the defect, reproduced")
        .doesNotContain(slugB);

    assertThat(lots.attribution(book))
        .as("the lots see both on the identical data")
        .extracting(PaperPositionLotRepository.AttributionRow::slug)
        .containsExactlyInAnyOrder(slugA, slugB);
  }

  /**
   * Coverage must report an untagged position as UNTAGGED rather than absent. A position written
   * straight to the table (standing in for the 45 that predate V057, which get no backfill) has no
   * lots, and an attribution read that silently ignored it would turn "not instrumented" into
   * "never traded".
   */
  @Test
  void aPositionWithNoLotsIsReportedUntaggedNotDropped() {
    String suffix = UUID.randomUUID().toString();
    String book = "lotcov-" + suffix.substring(0, 8);
    String sym = "LOTOPT-" + suffix;

    // A pre-V057-shaped row: inserted directly, so it has an order-less, lot-less history.
    long legacyId =
        positions.insertOpen(
            book, "NFO", sym + "-LEGACY", "BUY", 100L, new BigDecimal("50.00"), null, null);
    paper.closePosition(legacyId, new BigDecimal("55.00"));

    PaperPositionLotRepository.Coverage before = lots.coverage(book);
    assertThat(before.closedPositions()).as("the legacy trade is COUNTED").isEqualTo(1);
    assertThat(before.closedPositionsTagged()).as("but reported as untagged").isZero();
    assertThat(before.closedQty()).isEqualTo(100L);
    assertThat(before.closedQtyTagged()).isZero();
    assertThat(lots.attribution(book)).as("and contributes no attribution row").isEmpty();

    // A post-V057 fill in the same book is tagged, so coverage moves off zero.
    long signal = seedSignal("cov-" + suffix, suffix);
    paper.openOrder(order(book, sym, signal, 40, "100.00"));

    PaperPositionLotRepository.Coverage after = lots.coverage(book);
    assertThat(after.openPositions()).isEqualTo(1);
    assertThat(after.openPositionsTagged()).as("the new fill IS tagged").isEqualTo(1);
    assertThat(after.openQtyTagged()).isEqualTo(40L);
    assertThat(after.closedPositionsTagged())
        .as("and the legacy trade is still untagged — no backfill invented")
        .isZero();
  }

  /** A manual fill carries no signal: it must be reported under a null slug, not dropped. */
  @Test
  void anUnsignalledFillIsAttributedToANullSlugRatherThanLost() {
    String suffix = UUID.randomUUID().toString();
    String book = "lotman-" + suffix.substring(0, 8);
    String sym = "LOTOPT-" + suffix;

    paper.openOrder(order(book, sym, null, 25, "80.00"));

    List<PaperPositionLotRepository.AttributionRow> rows = lots.attribution(book);
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().slug()).as("no signal → no slug, reported as such").isNull();
    assertThat(rows.getFirst().openQty()).as("its quantity is still counted").isEqualTo(25L);
  }

  /**
   * The ALL-BOOKS path — {@code book} omitted, which is the endpoint's DEFAULT and therefore the
   * shape most likely to be called first.
   *
   * <p>Worth its own test because a null filter is not free here: both queries carry a {@code
   * ?::text IS NULL} guard, and without that explicit cast PostgreSQL cannot infer the parameter's
   * type and the whole read fails — on the default path only, so every book-scoped test above would
   * still pass.
   */
  @Test
  void theAllBooksReadResolvesTheNullFilterRatherThanFailing() throws Exception {
    String suffix = UUID.randomUUID().toString();
    String book = "lotall-" + suffix.substring(0, 8);
    String slug = "all-golden-" + suffix;
    long signal = seedSignal(slug, suffix);

    paper.openOrder(order(book, "LOTOPT-" + suffix, signal, 30, "150.00"));

    assertThat(lots.attribution(null))
        .as("the unfiltered decomposition resolves and contains this book's row")
        .anySatisfy(
            r -> {
              assertThat(r.slug()).isEqualTo(slug);
              assertThat(r.book()).isEqualTo(book);
            });
    assertThat(lots.coverage(null).openPositionsTagged())
        .as("and unfiltered coverage counts at least the one just tagged")
        .isGreaterThanOrEqualTo(1);

    mockMvc
        .perform(get("/api/v1/paper/attribution"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.coverage").exists());
  }

  /** The endpoint serves the decomposition with its coverage block, typed, over the wire. */
  @Test
  void theAttributionEndpointServesRowsAndCoverage() throws Exception {
    String suffix = UUID.randomUUID().toString();
    String book = "lotapi-" + suffix.substring(0, 8);
    String sym = "LOTOPT-" + suffix;
    String slug = "api-golden-" + suffix;
    long signal = seedSignal(slug, suffix);

    paper.openOrder(order(book, sym, signal, 50, "120.00"));

    mockMvc
        .perform(get("/api/v1/paper/attribution").param("book", book))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].slug").value(slug))
        .andExpect(jsonPath("$.items[0].openQty").value(50))
        .andExpect(jsonPath("$.coverage.openPositions").value(1))
        .andExpect(jsonPath("$.coverage.openPositionsTagged").value(1))
        // BigDecimal is a JSON STRING platform-wide (ArthaJacksonAutoConfiguration's
        // ToStringSerializer) — asserted as such so a retype to a number would fail here.
        .andExpect(jsonPath("$.items[0].attributedRealizedPnl").isString());
  }

  /**
   * The V057 grants, asserted under {@code SET ROLE ay_strategy} — the only way they are observable.
   *
   * <p>Services connect as {@code artha} (the D10 single-writer convention), which MASKS a missing
   * grant entirely: every test above, the endpoint, and the live write path all pass with no grant
   * at all. It fails only under {@code SET ROLE}, which is what the rest of this lineage pins and
   * what a future least-privilege move would use everywhere at once. Cross-vendor review caught the
   * omission; this is what stops it recurring.
   *
   * <p>Also pins that the table is APPEND-ONLY by grant — a lot's (signal, qty, price) is a
   * historical fact, so UPDATE and DELETE are denied to this role exactly as {@code
   * composite_rejections} denies them.
   */
  @Test
  void theLotsTableIsReadableAndAppendOnlyForTheReadOnlyRole() throws SQLException {
    String suffix = UUID.randomUUID().toString();
    String book = "lotrole-" + suffix.substring(0, 8);
    long signal = seedSignal("role-golden-" + suffix, suffix);
    // A real fill, so there is a genuine (position, order) pair for the role's INSERT to reference.
    paper.openOrder(order(book, "LOTOPT-" + suffix, signal, 20, "90.00"));
    Long positionId =
        jdbc.queryForObject(
            "SELECT position_id FROM paper_position_lots WHERE book = ?", Long.class, book);
    // A SECOND, lot-less order for the role's INSERT to reference: uq_paper_position_lots_order is
    // one-lot-per-fill, so reusing the fill above would trip that constraint rather than the grant
    // (it did, on the first run of this test — the index is reachable by hand even though
    // insertFilled's fresh identity makes it unreachable from the production path).
    long freeOrderId =
        orders.insertFilled(
            book, signal, "NFO", "LOTROLE-" + suffix, "BUY", 5L, new BigDecimal("1.00"),
            "ltp_slippage/v1", BigDecimal.ZERO, null, null);

    try (Connection conn = DriverManager.getConnection(jdbcUrl(), dbUser(), dbPassword());
        Statement st = conn.createStatement()) {
      st.execute("SET ROLE ay_strategy");
      st.execute("SET search_path TO strategy");

      // SELECT allowed — without the table grant this alone throws, which is the whole finding.
      try (ResultSet rs =
          st.executeQuery("SELECT count(*) FROM paper_position_lots WHERE book = '" + book + "'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).as("the read-only role can READ the lots").isEqualTo(1L);
      }

      // INSERT allowed, identity column included — this is what proves the sequence is reachable.
      st.execute(
          "INSERT INTO paper_position_lots"
              + " (position_id, order_id, signal_id, book, exchange, tradingsymbol, side, qty, fill_price)"
              + " VALUES (" + positionId + ", " + freeOrderId + ", " + signal + ", '" + book + "-role',"
              + " 'NFO', 'LOTROLE-" + suffix + "', 'BUY', 5, 1.0000)");

      // UPDATE / DELETE denied — lots are append-only by grant, as the lineage's doctrine requires.
      assertThatThrownBy(() -> st.execute("UPDATE paper_position_lots SET qty = 1"))
          .as("a lot is a historical fact — the read-only role must not rewrite one")
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> st.execute("DELETE FROM paper_position_lots"))
          .as("nor delete one")
          .isInstanceOf(SQLException.class);
    }
  }

  private static PaperService.OrderRequest order(
      String book, String sym, Long signalId, long qty, String price) {
    return new PaperService.OrderRequest(
        signalId, "NFO", sym, "BUY", qty, new BigDecimal(price), null, null, null, book, null);
  }

  private static BigDecimal shareOf(
      List<PaperPositionLotRepository.AttributionRow> rows, String slug) {
    Optional<PaperPositionLotRepository.AttributionRow> row =
        rows.stream().filter(r -> slug.equals(r.slug())).findFirst();
    return row.orElseThrow().attributedRealizedPnl();
  }

  /** Seeds strategy → published version → ENTRY signal; returns the signal id. */
  private long seedSignal(String slug, String suffix) {
    UUID strategyId =
        jdbc.queryForObject(
            "INSERT INTO strategies (slug, name, tags) VALUES (?, ?, '{}'::text[]) RETURNING id",
            UUID.class,
            slug,
            "LOT " + suffix);
    UUID versionId =
        jdbc.queryForObject(
            """
            INSERT INTO strategy_versions
              (strategy_id, version, config_yaml, config, schema_version, checksum, status)
            VALUES (?, '1', '', '{}'::jsonb, '1', ?, 'published') RETURNING id
            """,
            UUID.class,
            strategyId,
            "chk-" + suffix);
    return jdbc.queryForObject(
        """
        INSERT INTO signals
          (strategy_version_id, exchange, tradingsymbol, "interval", signal_type, side,
           composite_score, score_breakdown)
        VALUES (?, 'NFO', 'LOTSIG', '3m', 'ENTRY', 'BUY', 0.7, '{}'::jsonb) RETURNING id
        """,
        Long.class,
        versionId);
  }
}
