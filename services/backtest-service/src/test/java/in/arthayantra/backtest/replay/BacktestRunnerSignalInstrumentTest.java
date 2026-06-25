package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.series.SeriesKey;
import org.junit.jupiter.api.Test;

/**
 * {@link BacktestRunner#signalInstrument} resolves the single signal instrument for the v1 engine. The
 * options/futures-of-underlying case carries the underlying as a schema-v1 instrumentRef OBJECT
 * {@code {exchange, tradingsymbol}} — the pre-2026-06-25 code only parsed a legacy {@code "EXCH:SYMBOL"}
 * string, so a schema-valid options strategy threw "needs an explicit single-instrument universe". This
 * pins all three accepted shapes + the throw (caught live by the Part-2 value-verify).
 */
class BacktestRunnerSignalInstrumentTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private JsonNode cfg(String json) {
    try {
      return mapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void resolvesUnderlyingObjectForOptionsOfUnderlying() {
    SeriesKey k =
        BacktestRunner.signalInstrument(
            cfg(
                "{\"universe\":{\"mode\":\"options_of_underlying\","
                    + "\"underlying\":{\"exchange\":\"NSE\",\"tradingsymbol\":\"NIFTY 50\"}}}"));
    assertThat(k.exchange()).isEqualTo("NSE");
    assertThat(k.tradingsymbol()).isEqualTo("NIFTY 50");
    assertThat(k.interval()).isEqualTo("1m");
  }

  @Test
  void resolvesExplicitInstrumentsArray() {
    SeriesKey k =
        BacktestRunner.signalInstrument(
            cfg("{\"universe\":{\"instruments\":[{\"exchange\":\"NSE\",\"tradingsymbol\":\"RELIANCE\"}]}}"));
    assertThat(k.tradingsymbol()).isEqualTo("RELIANCE");
  }

  @Test
  void resolvesLegacyColonStringUnderlying() {
    SeriesKey k =
        BacktestRunner.signalInstrument(cfg("{\"universe\":{\"underlying\":\"NSE:NIFTY 50\"}}"));
    assertThat(k.exchange()).isEqualTo("NSE");
    assertThat(k.tradingsymbol()).isEqualTo("NIFTY 50");
  }

  @Test
  void throwsWhenNoResolvableInstrument() {
    assertThatThrownBy(() -> BacktestRunner.signalInstrument(cfg("{\"universe\":{\"mode\":\"index\"}}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("single-instrument universe");
  }
}
