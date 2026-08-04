package in.arthayantra.strategysignal.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.insights.InsightGenerator.GenerationContext;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RISK_STALE_TICK dedupe-key collision (the fix in {@link StaleTickGenerator}).
 *
 * <p><b>The defect.</b> The key was {@code RISK_STALE_TICK:<exchange>:<symbol>:<istDay>} — an
 * INSTRUMENT identity, not a POSITION identity. {@code uq_paper_positions_open} is
 * {@code (book, exchange, tradingsymbol, side) WHERE status='OPEN'} (V021), so one instrument can back
 * several open bracketed positions at once, and {@link PortfolioReader#staleTickScan} already returns
 * each of them as its own {@code StaleBracket}. Both became candidates with the SAME key, and
 * {@link InsightRepository#insertOrRefresh} upserts {@code ON CONFLICT (dedupe_key) WHERE
 * status='OPEN'} — so the second stalled bracket was folded into the first as a "refresh" and the
 * operator lost it. Book and side appeared only in the free-text body, so the surviving row did not
 * even say which position it described.
 *
 * <p><b>Why the fixture is a TWIN.</b> A single-position fixture cannot fail — it was exactly the
 * fixture the golden test already had, and it never noticed. Both shapes below are reachable on main
 * TODAY with no feature flag: cross-book was measured live on 2026-08-04 (NSE:AVALON, NSE:KANORICHEM,
 * NSE:PRECOT each open in both {@code manas-arora} and {@code minervini}), and V021's own comment
 * states the intent — "two books may each hold the same (exchange, symbol, side) long at once".
 *
 * <p>ITs share the singleton DB with no per-method cleanup, so every method mints a unique
 * tradingsymbol and therefore a dedupe-key namespace that cannot collide with a sibling or a rerun.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class StaleTickDedupeIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private InsightRepository repository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void twoBooksHoldingOneStalledInstrumentEachKeepTheirOwnInsightRow() {
    String symbol = "TWINBOOK" + UUID.randomUUID().toString().substring(0, 8);
    runRiskSweepOver(
        bracket(9001L, "minervini", symbol, "BUY"), bracket(9002L, "manas-arora", symbol, "BUY"));

    // The consequence the operator actually feels: BOTH stalled brackets survive as their own row.
    assertThat(openRowsFor(symbol))
        .as("two open bracketed positions on one instrument must produce two insight rows")
        .isEqualTo(2);
    assertThat(distinctDedupeKeysFor(symbol)).isEqualTo(2);
  }

  @Test
  void oneBookHoldingBothSidesOfOneStalledInstrumentKeepsBothInsightRows() {
    String symbol = "TWINSIDE" + UUID.randomUUID().toString().substring(0, 8);
    runRiskSweepOver(
        bracket(9003L, "scalper", symbol, "BUY"), bracket(9004L, "scalper", symbol, "SELL"));

    assertThat(openRowsFor(symbol))
        .as("a long and a short bracket on one instrument must produce two insight rows")
        .isEqualTo(2);
    assertThat(distinctDedupeKeysFor(symbol)).isEqualTo(2);
  }

  /**
   * The fixture discriminates independently of persistence: the generator itself must mint distinct
   * keys and distinct operator-facing titles. Asserted WITHOUT the repository so a failure here
   * localizes to the key template rather than to the upsert.
   */
  @Test
  void generatorMintsDistinctKeysAndDistinctTitlesForSiblingPositions() {
    List<InsightCandidate> out =
        new StaleTickGenerator()
            .generate(
                GenerationContext.forRisk(
                    null,
                    new StaleTickSnapshot(
                        List.of(
                            bracket(9005L, "minervini", "TWINPURE", "BUY"),
                            bracket(9006L, "manas-arora", "TWINPURE", "BUY"))),
                    OffsetDateTime.parse("2026-08-04T10:00:00+05:30")));

    assertThat(out).hasSize(2);
    assertThat(out).extracting(InsightCandidate::dedupeKey).doesNotHaveDuplicates();
    assertThat(out)
        .as("two alerts on one symbol are useless if the operator cannot tell them apart")
        .extracting(InsightCandidate::title)
        .doesNotHaveDuplicates();
    assertThat(out).extracting(InsightCandidate::title).allMatch(t -> t.contains("TWINPURE"));
  }

  /** Drives the REAL engine risk sweep over a stubbed portfolio read, against the REAL repository. */
  private void runRiskSweepOver(StaleTickSnapshot.StaleBracket... brackets) {
    PortfolioReader portfolioReader = mock(PortfolioReader.class);
    ContextClient contextClient = mock(ContextClient.class);
    when(contextClient.staleFeedKeys()).thenReturn(Map.of("ignored", "stub"));
    when(portfolioReader.staleTickScan(any())).thenReturn(new StaleTickSnapshot(List.of(brackets)));

    InsightPublisher publisher =
        new InsightPublisher(
            mock(StringRedisTemplate.class),
            objectMapper,
            mock(ApplicationEventPublisher.class),
            repository,
            properties(new InsightProperties.Delivery(false, false, false, Severity.NOTICE)));
    new InsightEngine(
            List.of(new StaleTickGenerator()),
            repository,
            mock(TrustService.class),
            mock(BookHeatReader.class),
            contextClient,
            mock(RejectionReader.class),
            portfolioReader,
            mock(StrategyEvidenceReader.class),
            publisher,
            properties(null),
            EngineStamp.of("test", "hash"),
            objectMapper,
            Clock.systemUTC(),
            new SimpleMeterRegistry())
        .runRiskSweep();
  }

  private static StaleTickSnapshot.StaleBracket bracket(
      long positionId, String book, String symbol, String side) {
    return new StaleTickSnapshot.StaleBracket(
        positionId, book, "NSE", symbol, side, true, true, "NSE:" + symbol,
        "ticks flowing but no 1m bar closed for 240s");
  }

  private int openRowsFor(String symbol) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM insights WHERE type = 'RISK_STALE_TICK' AND status = 'OPEN'"
            + " AND dedupe_key LIKE ?",
        Integer.class,
        "%:" + symbol + ":%");
  }

  private int distinctDedupeKeysFor(String symbol) {
    return jdbc.queryForObject(
        "SELECT count(DISTINCT dedupe_key) FROM insights WHERE type = 'RISK_STALE_TICK'"
            + " AND dedupe_key LIKE ?",
        Integer.class,
        "%:" + symbol + ":%");
  }

  private static InsightProperties properties(InsightProperties.Delivery delivery) {
    return new InsightProperties(null, null, null, null, null, null, null, null, null, delivery);
  }
}
