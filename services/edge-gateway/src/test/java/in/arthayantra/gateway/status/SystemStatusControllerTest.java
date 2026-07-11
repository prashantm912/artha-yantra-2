package in.arthayantra.gateway.status;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Register §9-22: the market-data rollup status used to read UP forever from the mere presence of the
 * {@code kite:session:status} key. It now degrades when the tick heartbeat is stale DURING market
 * hours, while staying quiet off-hours (no ticks then) and UNKNOWN when the key is absent.
 */
class SystemStatusControllerTest {

  private static final long FRESH = 10_000L; // 10 s
  private static final long STALE = 600_000L; // 10 min (> the 5 min bound)

  @Test
  void absentKeyIsUnknown() {
    assertThat(SystemStatusController.marketDataStatus(null, FRESH, "OPEN")).isEqualTo("UNKNOWN");
  }

  @Test
  void openWithFreshTicksIsUp() {
    assertThat(SystemStatusController.marketDataStatus("CONNECTED", FRESH, "OPEN")).isEqualTo("UP");
  }

  @Test
  void openWithStaleTicksIsDegraded() {
    assertThat(SystemStatusController.marketDataStatus("CONNECTED", STALE, "OPEN"))
        .isEqualTo("DEGRADED");
  }

  @Test
  void openWithNoTickHeartbeatIsDegraded() {
    assertThat(SystemStatusController.marketDataStatus("CONNECTED", null, "OPEN"))
        .isEqualTo("DEGRADED");
  }

  @Test
  void offHoursStaleTicksStayUp() {
    // No ticks flow when the market is CLOSED / PRE_OPEN, so staleness is not a health signal there.
    assertThat(SystemStatusController.marketDataStatus("CONNECTED", STALE, "CLOSED")).isEqualTo("UP");
    assertThat(SystemStatusController.marketDataStatus("MOCK", null, "PRE_OPEN")).isEqualTo("UP");
  }

  @Test
  void rateBudgetParsesTheProducerValueAndToleratesAbsentOrGarbage() {
    // F6: kite.rateBudget is now produced (market-data KiteRateBudgetPublisher → kite:rate-budget).
    assertThat(SystemStatusController.parseRateBudget("0.42")).isEqualTo(0.42);
    assertThat(SystemStatusController.parseRateBudget("1.0")).isEqualTo(1.0);
    assertThat(SystemStatusController.parseRateBudget(null)).isNull(); // no producer has run yet
    assertThat(SystemStatusController.parseRateBudget("")).isNull();
    assertThat(SystemStatusController.parseRateBudget("n/a")).isNull();
  }
}
