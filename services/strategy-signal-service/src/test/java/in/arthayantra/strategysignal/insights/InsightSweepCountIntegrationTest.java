package in.arthayantra.strategysignal.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.insights.InsightProperties.Delivery;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ledger H25: a sweep that ran and legitimately found nothing must be distinguishable from a sweep
 * that never fired.
 *
 * <p>Before this, every {@code run*Sweep()} returned {@code void} and logged only on failure, so
 * both cases produced byte-identical evidence — no log line, no row. Proving the 2026-08-17 18:57
 * sell-decision sweep had in fact run correctly took a code read plus four DB queries, and the only
 * positive evidence available was a sibling method on the same bean having written rows a minute
 * earlier.
 *
 * <p>These pin the contract that replaced it: the sweep returns a COUNT, and <b>zero is a real
 * answer</b>. A count that is always zero would silently reintroduce the ambiguity, which is what
 * the second test exists to catch.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class InsightSweepCountIntegrationTest extends StrategySignalIntegrationTestBase {
  /** Fixed so the CONTEXT_SHIFT phone budget has a deterministic IST day. */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-20T06:00:00Z"), ZoneOffset.UTC);

  private static final String SYMBOL = "H25CNT";

  @Autowired private InsightRepository repository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;

  /**
   * ⚠️ The ITs share ONE singleton DB with no per-method cleanup, and state survives surefire
   * RERUNS as well as methods. Without this, {@link #aSweepWithOneUnacknowledgedSellReportsItAsNEW}
   * passes in isolation and fails in the full suite (or on a second run) because the row it expects
   * to be NEW already exists from the previous pass — measured, not hypothesised: that is exactly
   * how it first failed. The dedupe key is {@code SELL_DECISION:<sellDecisionId>}, so purging this
   * class's two ids is sufficient and touches nothing else.
   */
  @BeforeEach
  @AfterEach
  void purgeOwnInsights() {
    jdbc.update("DELETE FROM insights WHERE dedupe_key IN (?,?)", "SELL_DECISION:1", "SELL_DECISION:2");
  }

  @Test
  void aSweepWithNothingToReportSaysSoRatherThanSayingNothing() {
    InsightEngine.SweepResult r = runSellDecisionSweepOver(List.of());
    assertThat(r.fresh()).isZero();
    assertThat(r.refreshed()).isZero();
    assertThat(r).hasToString("0 new / 0 refreshed");
  }

  @Test
  void aSweepWithOneUnacknowledgedSellReportsItAsNEW() {
    InsightEngine.SweepResult r = runSellDecisionSweepOver(List.of(sell(1L)));
    assertThat(r.fresh()).isEqualTo(1);
    assertThat(r.refreshed()).isZero();
  }

  /**
   * The discriminating case, and the reason the count is split. A single persistent condition
   * REFRESHES its existing OPEN row on every sweep — so an implementation that simply counted
   * candidates (or counted every non-throwing persist as a write) would report "1 insight" here,
   * forever, every five minutes, and an operator would read that as 288 insights a day.
   */
  @Test
  void theSameSellOnASecondSweepIsAREFRESH_NOT_ASECONDINSIGHT() {
    runSellDecisionSweepOver(List.of(sell(2L)));

    InsightEngine.SweepResult again = runSellDecisionSweepOver(List.of(sell(2L)));

    assertThat(again.fresh()).isZero();
    assertThat(again.refreshed()).isEqualTo(1);
  }

  /** Builds the engine the same way {@code StaleTickDedupeIntegrationTest} does, one generator. */
  private InsightEngine.SweepResult runSellDecisionSweepOver(List<SellDecisionInputs.SellRow> sells) {
    StrategyEvidenceReader evidenceReader = mock(StrategyEvidenceReader.class);
    when(evidenceReader.sellDecisionScan())
        .thenReturn(new SellDecisionInputs(LocalDate.of(2026, 8, 18), sells));

    InsightPublisher publisher =
        new InsightPublisher(
            mock(StringRedisTemplate.class),
            objectMapper,
            mock(ApplicationEventPublisher.class),
            repository,
            properties(new Delivery(false, false, false, Severity.NOTICE, 6)),
            CLOCK,
            new SimpleMeterRegistry());

    return new InsightEngine(
            List.of(new SellDecisionGenerator(properties(null))),
            repository,
            mock(TrustService.class),
            mock(BookHeatReader.class),
            mock(ContextClient.class),
            mock(RejectionReader.class),
            mock(PortfolioReader.class),
            evidenceReader,
            publisher,
            properties(null),
            EngineStamp.of("test", "hash"),
            objectMapper,
            Clock.systemUTC(),
            new SimpleMeterRegistry())
        .runSellDecisionSweep();
  }

  private static InsightProperties properties(Delivery delivery) {
    return new InsightProperties(null, null, null, null, null, null, null, null, null, delivery);
  }

  private static SellDecisionInputs.SellRow sell(long id) {
    return new SellDecisionInputs.SellRow(
        id,
        "minervini",
        LocalDate.of(2026, 8, 18),
        900L + id,
        "NSE",
        SYMBOL + id,
        "vcp",
        "SELL",
        "stop breached",
        new BigDecimal("100.00"),
        new BigDecimal("92.00"),
        new BigDecimal("-8.00"),
        1L);
  }
}
