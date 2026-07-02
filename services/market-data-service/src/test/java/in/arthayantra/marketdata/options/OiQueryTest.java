package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.ApiException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OiQueryTest {

  @Test
  void buildsLiveQueryWithDefaultInterval() {
    OiQuery q = OiQuery.of("live", "NIFTY 50", null, null, null);
    assertThat(q.live()).isTrue();
    assertThat(q.interval()).isEqualTo(OiInterval.M3); // default
    assertThat(q.name()).isEqualTo("NIFTY 50");
  }

  @Test
  void historyModeRequiresDate() {
    assertThatThrownBy(() -> OiQuery.of("history", "NIFTY 50", null, "5m", null))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("VALIDATION_FAILED"));
  }

  @Test
  void blankNameRejected() {
    assertThatThrownBy(() -> OiQuery.of("live", "  ", null, "3m", null))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void parsesDateAndExpiry() {
    OiQuery q = OiQuery.of("history", "NIFTY 50", "2026-06-20", "15m", "2026-06-25");
    assertThat(q.live()).isFalse();
    assertThat(q.date()).isEqualTo(LocalDate.of(2026, 6, 20));
    assertThat(q.expiry()).isEqualTo(LocalDate.of(2026, 6, 25));
    assertThat(q.interval()).isEqualTo(OiInterval.M15);
  }

  @Test
  void liveModeDropsALeakedDateParam() {
    // T4 (audit 2026-07-02 §9.3): a History date left in the client store must not time-travel a
    // "Live"-labelled response — live queries never carry a date.
    OiQuery q = OiQuery.of("live", "NIFTY 50", "2026-06-20", "3m", "2026-06-25");
    assertThat(q.live()).isTrue();
    assertThat(q.date()).isNull();
  }
}
