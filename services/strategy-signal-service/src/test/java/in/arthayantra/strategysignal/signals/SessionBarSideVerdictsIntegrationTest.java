package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * G16: the SESSION-WIDE near-miss evidence read
 * ({@link SignalRejectionRepository#sessionBarSideDiagnostics}). The fan-out collapse and the window
 * bound are DATABASE behaviour — {@code DISTINCT ON (bar_time, side)} and the {@code generated_at}
 * predicate — so a mock-repo unit test cannot pin either; they are asserted here against the real
 * lineage.
 *
 * <p>Shared singleton DB with no per-method cleanup, and every row this suite inserts lands with
 * {@code generated_at = now()} alongside whatever other suites wrote. Every assertion is therefore
 * scoped to THIS method's unique {@code bar_time} instants, never to the raw result size.
 */
@SpringBootTest(
    properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SessionBarSideVerdictsIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private SignalRejectionRepository rejections;
  @Autowired private JdbcTemplate jdbc;

  private static final String SCORED =
      "{\"context\":{\"macro\":{\"advances\":31,\"declines\":20}},\"confluence\":{\"dots\":"
          + "[{\"dot\":\"breadth\",\"weight\":1.0,\"supports\":false,\"reason\":\"r\"}]}}";
  private static final String EARLY_RAIL = "{\"checks\":[{\"rail\":\"time-window\",\"pass\":false}]}";

  private UUID versionId() {
    return jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
  }

  private long insert(String slug, String side, OffsetDateTime barTime, String diagnostic) {
    return rejections.insert(
        versionId(), slug, "NFO", "NIFTY26JULFUT", "3m", side, "confluence-composite",
        new BigDecimal("31"), new BigDecimal("32"), new BigDecimal("-1"), "advances 31 <= 32",
        new BigDecimal("0.55"), new BigDecimal("0.60"), diagnostic, barTime);
  }

  /** Today's rows, read through a window that brackets `now()` (never a wall-clock IST day —
   * generated_at is the real clock, so a fixed session window makes the test time-of-day dependent). */
  private List<SignalRejectionRepository.BarSideDiagnostic> readAroundNow() {
    OffsetDateTime now = OffsetDateTime.now();
    return rejections.sessionBarSideDiagnostics(now.minusHours(1), now.plusHours(1));
  }

  @Test
  void fanOutAcrossStrategiesCollapsesToOneVerdictPerBarAndSide() {
    // THE reason this read exists as an aggregate: one 3m bar fans out across ~38 scalpers carrying
    // the same market-wide operand, so a row tally would be skewed by how many strategies happened
    // to evaluate. Twelve rows here — 6 slugs x 2 bars on CE, plus both sides on one bar.
    OffsetDateTime barOne = OffsetDateTime.parse("2031-03-04T09:18:00+05:30");
    OffsetDateTime barTwo = barOne.plusMinutes(3);
    for (int i = 0; i < 6; i++) {
      insert("g16-fanout-" + i, "CE", barOne, SCORED);
      insert("g16-fanout-" + i, "CE", barTwo, SCORED);
    }
    insert("g16-fanout-pe", "PE", barOne, SCORED);

    List<SignalRejectionRepository.BarSideDiagnostic> mine =
        readAroundNow().stream()
            .filter(g -> g.barTime().isEqual(barOne) || g.barTime().isEqual(barTwo))
            .toList();

    assertThat(mine)
        .as("13 rows -> 3 verdicts: (barOne,CE), (barOne,PE), (barTwo,CE)")
        .hasSize(3);
    assertThat(mine)
        .extracting(g -> g.barTime().toInstant() + "/" + g.side())
        .containsExactlyInAnyOrder(
            barOne.toInstant() + "/CE", barOne.toInstant() + "/PE", barTwo.toInstant() + "/CE");
    assertThat(mine)
        .as("the representative row carries the scored breakdown the probe reads")
        .allSatisfy(g -> assertThat(g.diagnostic().at("/confluence/dots/0/dot").asText())
            .isEqualTo("breadth"));
  }

  @Test
  void rowsOutsideTheWindowAndEarlyRailRowsAreExcluded() {
    OffsetDateTime inWindow = OffsetDateTime.parse("2031-03-05T09:18:00+05:30");
    OffsetDateTime aged = OffsetDateTime.parse("2031-03-05T09:21:00+05:30");
    OffsetDateTime contextless = OffsetDateTime.parse("2031-03-05T09:24:00+05:30");
    insert("g16-window-in", "CE", inWindow, SCORED);
    long agedId = insert("g16-window-aged", "CE", aged, SCORED);
    insert("g16-window-early", "CE", contextless, EARLY_RAIL);
    // Push one row's generated_at out of the read window — the bound is on generated_at, not on
    // bar_time, so this is the only way to prove the predicate does any work.
    jdbc.update(
        "UPDATE signal_rejections SET generated_at = now() - interval '3 hours' WHERE id = ?",
        agedId);

    List<OffsetDateTime> bars = readAroundNow().stream()
        .map(SignalRejectionRepository.BarSideDiagnostic::barTime)
        .toList();

    assertThat(bars).as("inside the window").anySatisfy(b -> assertThat(b).isEqualTo(inWindow));
    assertThat(bars).as("generated_at outside the window").noneSatisfy(
        b -> assertThat(b).isEqualTo(aged));
    assertThat(bars).as("early-rail block carries no dot verdicts at all").noneSatisfy(
        b -> assertThat(b).isEqualTo(contextless));
  }
}
