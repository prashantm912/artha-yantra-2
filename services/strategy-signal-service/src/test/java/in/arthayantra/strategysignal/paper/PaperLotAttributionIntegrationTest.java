package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
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
 * V056 per-signal lot tagging: a position built by TWO strategies firing on the same bar decomposes
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
              ? new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 50)
              : new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private PaperService paper;
  @Autowired private PaperPositionRepository positions;
  @Autowired private PaperPositionLotRepository lots;
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
        .as("the pre-V056 grouping sees ONE strategy where two traded")
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
   * straight to the table (standing in for the 45 that predate V056, which get no backfill) has no
   * lots, and an attribution read that silently ignored it would turn "not instrumented" into
   * "never traded".
   */
  @Test
  void aPositionWithNoLotsIsReportedUntaggedNotDropped() {
    String suffix = UUID.randomUUID().toString();
    String book = "lotcov-" + suffix.substring(0, 8);
    String sym = "LOTOPT-" + suffix;

    // A pre-V056-shaped row: inserted directly, so it has an order-less, lot-less history.
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

    // A post-V056 fill in the same book is tagged, so coverage moves off zero.
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
