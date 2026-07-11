package in.arthayantra.backtest.replay.deepswing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire mirror between market-data's {@code DeepSwingRunResponse} JSON and the backtest-side
 * {@link DeepSwingReply} (audit P0-3) — the one seam the lifecycle IT mocks away. The sample below is
 * shaped exactly as market-data serializes it: decimals as JSON STRINGS (the global Jackson config),
 * {@code LocalDate} as ISO dates, engine identity nullable, plus an EXTRA unknown field to prove
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps an additive market-data change from
 * breaking the worker.
 */
class DeepSwingReplyWireTest {

  private static final String SAMPLE =
      """
      {
        "engineSha": "abc123",
        "engineImage": "market-data-service:0.1.0",
        "futureField": {"added": "later"},
        "result": {
          "family": "manas",
          "variant": "rs-turnover-nopyramid",
          "fromDate": "2015-07-01",
          "runAt": "2026-07-12T10:00:00+05:30",
          "symbolsScanned": 1800,
          "capital": "1000000",
          "totalReturnPct": "250.50",
          "cagrPct": "12.34",
          "maxDrawdownPct": "30.20",
          "sharpe": "0.96",
          "tradesTaken": 100,
          "tradesSkipped": 40,
          "winRatePct": "48.1000",
          "profitFactor": "1.8000",
          "report": {"status": "completed", "totalTrades": 1},
          "trades": [
            {
              "symbol": "RELIANCE",
              "setup": "breakout",
              "entryDate": "2016-02-01",
              "entryPrice": "500.10",
              "exitDate": "2016-05-02",
              "exitPrice": "600.50",
              "pnlPct": "20.08",
              "barsHeld": 62,
              "exitReason": "TRAILING_STOP",
              "rsRankAtEntry": null,
              "futureTradeField": 7
            }
          ]
        }
      }
      """;

  @Test
  void marketDataShapedJsonBindsIncludingUnknownsAndNulls() throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    DeepSwingReply reply = mapper.readValue(SAMPLE, DeepSwingReply.class);

    assertThat(reply.engineSha()).isEqualTo("abc123");
    assertThat(reply.engineImage()).isEqualTo("market-data-service:0.1.0");
    DeepSwingReply.Result r = reply.result();
    assertThat(r.family()).isEqualTo("manas");
    assertThat(r.fromDate()).isEqualTo(LocalDate.of(2015, 7, 1));
    assertThat(r.totalReturnPct()).isEqualByComparingTo("250.50");
    assertThat(r.report().path("status").asText()).isEqualTo("completed");
    assertThat(r.trades()).hasSize(1);
    DeepSwingReply.Trade t = r.trades().get(0);
    assertThat(t.symbol()).isEqualTo("RELIANCE");
    assertThat(t.entryDate()).isEqualTo(LocalDate.of(2016, 2, 1));
    assertThat(t.pnlPct()).isEqualByComparingTo("20.08");
    assertThat(t.exitReason()).isEqualTo("TRAILING_STOP");
    assertThat(t.rsRankAtEntry()).isNull(); // a NaN RS-rank rides as null, never fabricated
  }
}
