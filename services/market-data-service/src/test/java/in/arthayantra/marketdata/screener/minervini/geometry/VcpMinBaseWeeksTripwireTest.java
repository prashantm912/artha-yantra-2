package in.arthayantra.marketdata.screener.minervini.geometry;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * E4 decision-sheet §2g tripwire: {@code VcpDetector.baseWeeks} (a trailing-contraction-only
 * measurement, not the classical multi-week base) is a latent mismeasure that is harmless only
 * because the live {@code artha.minervini.vcp.min-base-weeks} floor is 0 (disabled) — re-arming
 * ANY positive floor without first fixing the measurement would reproduce M39's ~99% VCP trade
 * annihilation (docs/strategies/m39-vcp-caps-backtest-2026-07-06.md §4 "option 2"). The owner does
 * not want the measurement fixed (it is correctly dormant); they want arming to fail loudly.
 *
 * <p>The guard lives in the constructor, not a config-validation annotation or a bare default-value
 * assertion, because it must fire at every moment a positive value could reach it: any
 * {@code @SpringBootTest} that loads the full context re-constructs this {@code @Component} bean,
 * so a stray change to the compiled-in default (editing the {@code @Value(...:0)} literal itself, or
 * adding an {@code application.yml} override) fails the FIRST test that boots, not just this one. A
 * CI-only test asserting "the default is 0" would have missed exactly that case — it only reads the
 * constant, it never constructs the bean. The mechanism does NOT currently cover a live {@code .env}
 * arm: no docker-compose passthrough exists for any {@code artha.minervini.vcp.*} property today (a
 * repo-wide grep of {@code deploy/} confirms it), so an {@code .env} edit alone is silently ignored,
 * not tripped — a passthrough would need to be added first, and adding one is out of this task's
 * scope (deploy config, not the tripwire itself). If a passthrough is ever added, this guard already
 * covers it for free, since it fires on construction regardless of where the value came from.
 */
class VcpMinBaseWeeksTripwireTest {

  private static VcpDetector detectorWithFloor(int minBaseWeeks) {
    return new VcpDetector(2.5, 2, 6, 0.2, 0.9, 0.5, 0.5, 100, 40, 60, minBaseWeeks, 65);
  }

  @Test
  void armingAnyPositiveFloorTripsAtConstruction() {
    // The smallest possible arm (1), not just the historical M39 value (3) — the guard must trip on
    // ANY re-arm, not merely the specific number that bit the repo once before.
    assertThatThrownBy(() -> detectorWithFloor(1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("min-base-weeks=1")
        .hasMessageContaining("trailing narrowing contraction") // names the defect
        .hasMessageContaining("docs/signal-analysis/2026-08-01-e4-e8-decision-sheet.md"); // points at it
  }

  @Test
  void armingTheHistoricalM39FloorAlsoTrips() {
    assertThatThrownBy(() -> detectorWithFloor(3)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theLiveDefaultStaysSilent() {
    // min-base-weeks=0 is the shipped live default (VcpDetector.java's @Value(...:0)) — construction
    // must stay silent, else every existing VcpDetector-consuming test/bean would fail to boot.
    assertThatCode(() -> detectorWithFloor(0)).doesNotThrowAnyException();
  }
}
