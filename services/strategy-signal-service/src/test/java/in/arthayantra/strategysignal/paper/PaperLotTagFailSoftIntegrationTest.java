package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * V057 lot tagging is FAIL-SOFT: a failure to tag must never destroy the paper trade it describes.
 *
 * <p>Cross-vendor review Critical 2. The first cut wrote the lot inside the opening transaction with
 * no guard, so a throw rolled back the order AND the position — while {@code AutoPaperListener} had
 * already transitioned the signal to {@code TAKEN} and {@code PaperSignalListener} only LOGS the
 * failure with no scalper retry. A failure in a purely observational table could therefore leave a
 * taken signal with no position and nothing to reopen it. Attribution integrity is not worth a lost
 * trade.
 *
 * <p>This drives the real failure through a spied repository rather than simulating it, so it
 * exercises the actual savepoint. A plain try/catch would NOT survive: the failed statement poisons
 * the outer PostgreSQL transaction and every later statement in the fill is refused — so if the
 * savepoint were removed this test fails on the fill, not merely on the counter.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class PaperLotTagFailSoftIntegrationTest extends StrategySignalIntegrationTestBase {

  @TestConfiguration
  static class StubInstruments {
    @Bean
    @Primary
    InstrumentMetaClient stubInstrumentMetaClient() {
      return (exchange, tradingsymbol) ->
          new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 50);
    }
  }

  @MockitoSpyBean private PaperPositionLotRepository lots;
  @Autowired private PaperService paper;
  @Autowired private PaperPositionRepository positions;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  void aFailedLotTagLeavesTheTradeIntactAndCountsTheGap() {
    String suffix = UUID.randomUUID().toString();
    String book = "lotsoft-" + suffix.substring(0, 8);
    String sym = "LOTSOFT-" + suffix;

    doThrow(new DataIntegrityViolationException("simulated lot-tag failure"))
        .when(lots)
        .insert(anyLong(), anyLong(), any(), anyString(), anyString(), anyString(), anyString(),
            anyLong(), any());

    double before = lotTagFailures();

    // The fill must SUCCEED despite the tagging failure.
    PaperService.PositionDto opened =
        paper.openOrder(
            new PaperService.OrderRequest(
                null, "NFO", sym, "BUY", 50L, new BigDecimal("100.00"), null, null, null, book,
                null));

    assertThat(opened).as("the paper trade survives a tagging failure — the whole point").isNotNull();
    assertThat(positions.findOpen(book, "NFO", sym, "BUY"))
        .as("and the position is genuinely PERSISTED, not merely returned")
        .isPresent()
        .get()
        .extracting(PaperPositionRepository.PositionRow::qty)
        .isEqualTo(50L);

    assertThat(lotTagFailures())
        .as("the gap is COUNTED, so a silent hole in attribution is impossible")
        .isEqualTo(before + 1);

    // Coverage is what surfaces the consequence: the position exists and is untagged.
    PaperPositionLotRepository.Coverage coverage = realCoverage(book);
    assertThat(coverage.openPositions()).isEqualTo(1);
    assertThat(coverage.openPositionsTagged())
        .as("the missing lot shows up as UNTAGGED rather than as a missing trade")
        .isZero();
  }

  /**
   * The attribution read takes ONE snapshot for both its queries (cross-vendor review 3).
   *
   * <p>Rows and coverage are two statements. Under the default {@code READ_COMMITTED} each takes its
   * own snapshot, so an open or close landing between them yields a response whose rows and coverage
   * describe DIFFERENT database states — and since coverage exists precisely to be read against the
   * rows, that contradiction is worse than either being slightly stale.
   *
   * <p>Asserted deterministically rather than by racing a concurrent writer: both repository calls
   * must observe the SAME active transaction at {@code REPEATABLE_READ}. Remove the wrapping
   * template and the observed isolation goes null (no transaction), so this reddens on the literal
   * condition instead of flaking.
   */
  @Test
  void rowsAndCoverageAreReadInOneRepeatableReadSnapshot() {
    List<Integer> isolationDuringRows = new ArrayList<>();
    List<Integer> isolationDuringCoverage = new ArrayList<>();

    doAnswer(
            inv -> {
              isolationDuringRows.add(
                  TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
              return inv.callRealMethod();
            })
        .when(lots)
        .attribution(any());
    doAnswer(
            inv -> {
              isolationDuringCoverage.add(
                  TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
              return inv.callRealMethod();
            })
        .when(lots)
        .coverage(any());

    paper.attribution("lotsnap-" + UUID.randomUUID());

    assertThat(isolationDuringRows)
        .as("the rows query runs inside a REPEATABLE_READ transaction")
        .containsExactly(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThat(isolationDuringCoverage)
        .as("and so does the coverage query — the same snapshot, not a second one")
        .containsExactly(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  private double lotTagFailures() {
    return meterRegistry.find("ay_paper_lot_tag_failures_total").counter() == null
        ? 0d
        : meterRegistry.find("ay_paper_lot_tag_failures_total").counter().count();
  }

  /** Coverage read through the spy's REAL implementation (only {@code insert} is stubbed). */
  private PaperPositionLotRepository.Coverage realCoverage(String book) {
    return lots.coverage(book);
  }
}
