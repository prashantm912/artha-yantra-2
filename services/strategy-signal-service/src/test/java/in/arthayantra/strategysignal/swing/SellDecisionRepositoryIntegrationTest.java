package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V037 durable sell-decision store (audit §7.2.4 + §10 Phase 2): asserts the table applied, the
 * upsert→read→acknowledge round-trip, the idempotent-per-(run_date, signal_id) upsert that CLEARS an
 * ack only when the verdict flips, and the 404 on an unknown ack. Shared-DB safe: every method uses a
 * fresh signal id (seeded from nanoTime so reruns never collide on the unique key).
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SellDecisionRepositoryIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final AtomicLong SIG = new AtomicLong(System.nanoTime());
  private static final LocalDate RUN = LocalDate.of(2026, 7, 12);
  private static final String BOOK = "minervini";

  @Autowired private SellDecisionRepository repository;
  @Autowired private SellDecisionsController controller;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void v037TableExists() {
    assertThat(jdbc.queryForObject("SELECT to_regclass('strategy.sell_decisions')::text", String.class))
        .isEqualTo("sell_decisions");
  }

  @Test
  void upsertReadAndAcknowledgeRoundTrip() {
    long sig = SIG.incrementAndGet();
    repository.upsert(
        BOOK, RUN, sig, "NSE", "TCS", "minervini-vcp", 2, null, "3T 12w",
        new BigDecimal("3800"), new BigDecimal("3496"), new BigDecimal("-8.00"),
        new BigDecimal("3496"), new BigDecimal("3990"), false, true, "STOP_LOSS", "SELL (STOP_LOSS)");

    SellDecisionRepository.SellDecisionRow row = findBySig(sig).orElseThrow();
    assertThat(row.symbol()).isEqualTo("TCS");
    assertThat(row.setup()).isEqualTo("minervini-vcp");
    assertThat(row.stage()).isEqualTo(2);
    assertThat(row.verdict()).isEqualTo("SELL (STOP_LOSS)");
    assertThat(row.sellReason()).isEqualTo("STOP_LOSS");
    assertThat(row.stopLevel()).isEqualByComparingTo("3496");
    assertThat(row.acknowledgedAt()).isNull();

    // Acknowledge via the controller → the returned row + the re-read both carry acknowledged_at.
    SellDecisionRepository.SellDecisionRow acked = controller.acknowledge(row.id());
    assertThat(acked.acknowledgedAt()).isNotNull();
    assertThat(findBySig(sig).orElseThrow().acknowledgedAt()).isNotNull();
    // The unacknowledged filter now excludes it.
    assertThat(
            repository.recent(BOOK, true, 500, 0).stream().noneMatch(r -> r.signalId() == sig))
        .isTrue();
  }

  @Test
  void sameDayReRunPreservesAckUnlessTheVerdictChanges() {
    long sig = SIG.incrementAndGet();
    repository.upsert(
        BOOK, RUN, sig, "NSE", "INFY", "minervini-cheat", 2, null, null,
        new BigDecimal("1600"), new BigDecimal("1700"), new BigDecimal("6.25"),
        new BigDecimal("1520"), null, true, false, null, "HOLD");
    long id = findBySig(sig).orElseThrow().id();
    controller.acknowledge(id);
    assertThat(findBySig(sig).orElseThrow().acknowledgedAt()).isNotNull();

    // Same-day re-run, SAME verdict → the ack is preserved (a benign catch-up run must not re-surface it).
    repository.upsert(
        BOOK, RUN, sig, "NSE", "INFY", "minervini-cheat", 2, null, null,
        new BigDecimal("1600"), new BigDecimal("1690"), new BigDecimal("5.63"),
        new BigDecimal("1520"), null, true, false, null, "HOLD");
    assertThat(findBySig(sig).orElseThrow().acknowledgedAt()).isNotNull();

    // Verdict FLIPS (HOLD → SELL) → the ack clears so the new decision re-surfaces.
    repository.upsert(
        BOOK, RUN, sig, "NSE", "INFY", "minervini-cheat", 2, null, null,
        new BigDecimal("1600"), new BigDecimal("1470"), new BigDecimal("-8.13"),
        new BigDecimal("1470"), null, false, true, "STOP_LOSS", "SELL (STOP_LOSS)");
    SellDecisionRepository.SellDecisionRow flipped = findBySig(sig).orElseThrow();
    assertThat(flipped.verdict()).isEqualTo("SELL (STOP_LOSS)");
    assertThat(flipped.acknowledgedAt()).isNull();
  }

  @Test
  void acknowledgeUnknownIdIs404() {
    assertThatThrownBy(() -> controller.acknowledge(Long.MAX_VALUE))
        .isInstanceOf(NotFoundException.class);
  }

  private Optional<SellDecisionRepository.SellDecisionRow> findBySig(long sig) {
    return repository.recent(BOOK, false, 500, 0).stream().filter(r -> r.signalId() == sig).findFirst();
  }
}
