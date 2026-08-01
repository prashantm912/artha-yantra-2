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
 * <p>⚠️ Every row this suite writes is stamped into a FIXED 2031 window, never {@code now()}, and is
 * read back through that same window. The shared singleton DB has no per-method cleanup, so a row
 * left in "today" is visible to every other IT's context — including {@code DotHealthCanary}, whose
 * sweep would then treat this suite's fixtures as live session evidence. Measured, not theorised:
 * with these rows landing at {@code now()} the full reactor went red in an unrelated engine IT
 * (twice, same assertion) and green with this class excluded. Fixed timestamps also make the
 * assertions independent of the wall clock, which a {@code now()}-relative window is not.
 */
@SpringBootTest(
    properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SessionBarSideVerdictsIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private SignalRejectionRepository rejections;
  @Autowired private JdbcTemplate jdbc;

  /** The isolated read window: a 2031 date no other suite writes into. */
  private static final OffsetDateTime WINDOW_FROM =
      OffsetDateTime.parse("2031-03-04T09:15:00+05:30");
  private static final OffsetDateTime WINDOW_TO = WINDOW_FROM.plusDays(1);

  private static final String SCORED =
      "{\"context\":{\"macro\":{\"advances\":31,\"declines\":20}},\"confluence\":{\"dots\":"
          + "[{\"dot\":\"breadth\",\"weight\":1.0,\"supports\":false,\"reason\":\"r\"}]}}";
  private static final String EARLY_RAIL = "{\"checks\":[{\"rail\":\"time-window\",\"pass\":false}]}";

  private UUID versionId() {
    return jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
  }

  /**
   * Inserts one rejection and stamps its {@code generated_at} into the fixed window — {@code insert}
   * defaults that column to {@code now()}, which is exactly what must not be left behind.
   */
  private void insertAt(
      String slug, String side, OffsetDateTime barTime, OffsetDateTime stamp, String diagnostic) {
    long id =
        rejections.insert(
            versionId(), slug, "NFO", "NIFTY26JULFUT", "3m", side, "confluence-composite",
            new BigDecimal("31"), new BigDecimal("32"), new BigDecimal("-1"), "advances 31 <= 32",
            new BigDecimal("0.55"), new BigDecimal("0.60"), diagnostic, barTime);
    jdbc.update("UPDATE signal_rejections SET generated_at = ? WHERE id = ?", stamp, id);
  }

  private List<SignalRejectionRepository.BarSideDiagnostic> read() {
    return rejections.sessionBarSideDiagnostics(WINDOW_FROM, WINDOW_TO);
  }

  @Test
  void fanOutAcrossStrategiesCollapsesToOneVerdictPerBarAndSide() {
    // THE reason this read exists as an aggregate: one 3m bar fans out across ~38 scalpers carrying
    // the same market-wide operand, so a row tally would be skewed by how many strategies happened
    // to evaluate. Thirteen rows here — 6 slugs x 2 bars on CE, plus a PE row on the first bar.
    OffsetDateTime barOne = WINDOW_FROM.plusMinutes(3);
    OffsetDateTime barTwo = barOne.plusMinutes(3);
    for (int i = 0; i < 6; i++) {
      insertAt("g16-fanout-" + i, "CE", barOne, barOne, SCORED);
      insertAt("g16-fanout-" + i, "CE", barTwo, barTwo, SCORED);
    }
    insertAt("g16-fanout-pe", "PE", barOne, barOne, SCORED);

    List<SignalRejectionRepository.BarSideDiagnostic> verdicts =
        read().stream()
            .filter(g -> g.barTime().isEqual(barOne) || g.barTime().isEqual(barTwo))
            .toList();

    assertThat(verdicts)
        .as("13 rows -> 3 verdicts: (barOne,CE), (barOne,PE), (barTwo,CE)")
        .hasSize(3);
    assertThat(verdicts)
        .extracting(g -> g.barTime().toInstant() + "/" + g.side())
        .containsExactlyInAnyOrder(
            barOne.toInstant() + "/CE", barOne.toInstant() + "/PE", barTwo.toInstant() + "/CE");
    assertThat(verdicts)
        .as("the representative row carries the scored breakdown the probe reads")
        .allSatisfy(
            g ->
                assertThat(g.diagnostic().at("/confluence/dots/0/dot").asText())
                    .isEqualTo("breadth"));
  }

  @Test
  void rowsOutsideTheWindowAndEarlyRailRowsAreExcluded() {
    OffsetDateTime inWindow = WINDOW_FROM.plusMinutes(30);
    OffsetDateTime aged = WINDOW_FROM.plusMinutes(33);
    OffsetDateTime contextless = WINDOW_FROM.plusMinutes(36);
    insertAt("g16-window-in", "CE", inWindow, inWindow, SCORED);
    // same bar_time neighbourhood, but generated_at a day BEFORE the window — the predicate is on
    // generated_at, so this is the only way to prove it does any work.
    insertAt("g16-window-aged", "CE", aged, WINDOW_FROM.minusDays(1), SCORED);
    insertAt("g16-window-early", "CE", contextless, contextless, EARLY_RAIL);

    List<OffsetDateTime> bars =
        read().stream().map(SignalRejectionRepository.BarSideDiagnostic::barTime).toList();

    assertThat(bars).as("inside the window").anySatisfy(b -> assertThat(b).isEqualTo(inWindow));
    assertThat(bars)
        .as("generated_at before the window")
        .noneSatisfy(b -> assertThat(b).isEqualTo(aged));
    assertThat(bars)
        .as("early-rail block carries no dot verdicts at all")
        .noneSatisfy(b -> assertThat(b).isEqualTo(contextless));
  }
}
