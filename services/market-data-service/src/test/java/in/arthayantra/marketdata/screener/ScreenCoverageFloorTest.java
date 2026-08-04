package in.arthayantra.marketdata.screener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.ApiException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Boundary arithmetic for the screen coverage floor (see {@link ScreenCoverageFloor}). */
class ScreenCoverageFloorTest {

  private static final LocalDate D = LocalDate.of(2026, 8, 3);

  @Test
  void passesExactlyAtTheFloor() {
    // 80 of 100 = exactly 80% — the floor is a MINIMUM, so this publishes.
    assertThatCode(() -> ScreenCoverageFloor.check("minervini", D, 80, 100, 80))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesOneSymbolBelowTheFloor() {
    assertThatThrownBy(() -> ScreenCoverageFloor.check("minervini", D, 79, 100, 80))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("REFUSED")
        .hasMessageContaining("79 of 100");
  }

  @Test
  void passesOnTodaysMeasuredLiveRatio() {
    // Measured live 2026-08-03: 1767 of 1776 Minervini / 2262 of 2274 Manas survive the guard
    // (~99.5%). The floor must be nowhere near tripping on a normal session.
    assertThatCode(() -> ScreenCoverageFloor.check("minervini", D, 1767, 1776, 80))
        .doesNotThrowAnyException();
    assertThatCode(() -> ScreenCoverageFloor.check("manas", D, 2262, 2274, 80))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesAPartialIngest() {
    // The case the floor exists for: a fifth of the universe printed, the rest still on yesterday.
    assertThatThrownBy(() -> ScreenCoverageFloor.check("manas", D, 455, 2274, 80))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("20.0%");
  }

  @Test
  void anEmptyUniverseIsNotAFloorBreach() {
    // Nothing accrued yet is a different condition, already handled by the callers (they publish
    // nothing). Guarding it here also keeps the ratio from dividing by zero.
    assertThatCode(() -> ScreenCoverageFloor.check("manas", D, 0, 0, 80))
        .doesNotThrowAnyException();
  }

  @Test
  void theMessageCarriesWhatOpsNeeds() {
    ApiException e =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> ScreenCoverageFloor.check("manas", D, 455, 2274, 80), ApiException.class);
    assertThat(e).isNotNull();
    assertThat(e.getMessage())
        .contains("manas")
        .contains("2026-08-03")
        .contains("455 of 2274")
        .contains("80%")
        .contains("Nothing was published");
  }
}
