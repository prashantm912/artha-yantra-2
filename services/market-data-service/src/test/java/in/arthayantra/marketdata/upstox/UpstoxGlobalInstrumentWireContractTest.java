package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the {@link UpstoxGlobalInstrument} wire DTO (the {@code kite/wire} anti-corruption
 * convention). Every CONSUMED field of a real global-master row — segment, name, country,
 * instrument_key, trading_symbol, exchange, latency — must round-trip into the record, and an UNKNOWN
 * field (e.g. {@code start_time}, a future addition) must never throw, so Upstox's additive master
 * changes can never crash the World Indices parse.
 */
class UpstoxGlobalInstrumentWireContractTest {

  private final ObjectMapper mapper = new ObjectMapper();

  // A HANG SENG row of the live global.json.gz shape, plus the extra documented fields we do NOT map
  // (start_time/end_time) — they must be tolerated, not crash the parse.
  private static final String BODY =
      """
      [{"segment":"GLOBAL_INDEX","name":"HANG SENG","country":"Hong Kong",
        "instrument_key":"GLOBAL_INDEX|^HSI","trading_symbol":"^HSI","exchange":"GLOBAL",
        "latency":"900 Seconds","weekly":false,
        "start_time":"09:30","end_time":"16:00"}]
      """;

  @Test
  void mirrorsEveryConsumedFieldAndToleratesUnmappedOnes() throws Exception {
    List<UpstoxGlobalInstrument> rows =
        mapper.readValue(BODY, new TypeReference<List<UpstoxGlobalInstrument>>() {});

    assertThat(rows).hasSize(1);
    UpstoxGlobalInstrument r = rows.get(0);
    assertThat(r.segment()).isEqualTo("GLOBAL_INDEX");
    assertThat(r.name()).isEqualTo("HANG SENG");
    assertThat(r.country()).isEqualTo("Hong Kong");
    assertThat(r.instrumentKey()).isEqualTo("GLOBAL_INDEX|^HSI");
    assertThat(r.tradingSymbol()).isEqualTo("^HSI");
    assertThat(r.exchange()).isEqualTo("GLOBAL");
    assertThat(r.latency()).isEqualTo("900 Seconds");
    assertThat(r.weekly()).isFalse();
  }

  @Test
  void ignoresBrandNewFieldsSoUpstoxAdditionsNeverCrashTheParse() {
    String withNewFields =
        """
        [{"segment":"GLOBAL_INDEX","name":"NIKKEI 225","instrument_key":"GLOBAL_INDEX|^N225",
          "galaxy_brain_field":42,"nested":{"x":1}}]
        """;

    assertThatCode(
            () -> {
              List<UpstoxGlobalInstrument> rows =
                  mapper.readValue(withNewFields, new TypeReference<List<UpstoxGlobalInstrument>>() {});
              assertThat(rows.get(0).name()).isEqualTo("NIKKEI 225");
              assertThat(rows.get(0).country()).isNull(); // absent field stays null, never throws
            })
        .doesNotThrowAnyException();
  }
}
