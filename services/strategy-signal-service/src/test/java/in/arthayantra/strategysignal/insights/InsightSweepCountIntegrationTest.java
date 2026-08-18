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
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;

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

  private static final String SYMBOL = "H25CNT";

  @Autowired private InsightRepository repository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void aSweepWithNothingToReportReturnsZeroRatherThanSayingNothing() {
    assertThat(runSellDecisionSweepOver(List.of())).isZero();
  }

  @Test
  void aSweepWithOneUnacknowledgedSellReturnsOne() {
    assertThat(runSellDecisionSweepOver(List.of(sell(1L)))).isEqualTo(1);
  }

  /** Builds the engine the same way {@code StaleTickDedupeIntegrationTest} does, one generator. */
  private int runSellDecisionSweepOver(List<SellDecisionInputs.SellRow> sells) {
    StrategyEvidenceReader evidenceReader = mock(StrategyEvidenceReader.class);
    when(evidenceReader.sellDecisionScan())
        .thenReturn(new SellDecisionInputs(LocalDate.of(2026, 8, 18), sells));

    InsightPublisher publisher =
        new InsightPublisher(
            mock(StringRedisTemplate.class),
            objectMapper,
            mock(ApplicationEventPublisher.class),
            repository,
            properties(new Delivery(false, false, false, Severity.NOTICE)));

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
