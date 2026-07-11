package in.arthayantra.backtest.replay.deepswing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The backtest-side mirror of market-data's {@code DeepSwingRunResponse} (audit P0-3) — the deep-sim
 * result the DEEP_SWING worker maps into {@code backtest_runs} + {@code backtest_trades}. Each record
 * is {@code @JsonIgnoreProperties(ignoreUnknown = true)} so a field market-data ADDS never breaks the
 * worker. {@code engineSha}/{@code engineImage} are the market-data jar's — the engine that computed
 * the numbers, stamped on the run row. {@code report} is the FULL per-variant deep-sim report, kept
 * verbatim in the run's metrics JSONB.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeepSwingReply(String engineSha, String engineImage, Result result) {

  /** The deep-sim run's headline + full report + per-trade rows. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Result(
      String family,
      String variant,
      LocalDate fromDate,
      String runAt,
      int symbolsScanned,
      BigDecimal capital,
      BigDecimal totalReturnPct,
      BigDecimal cagrPct,
      BigDecimal maxDrawdownPct,
      BigDecimal sharpe,
      int tradesTaken,
      int tradesSkipped,
      BigDecimal winRatePct,
      BigDecimal profitFactor,
      JsonNode report,
      List<Trade> trades) {}

  /** One closed swing lot (the shared deep-sim wire trade). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Trade(
      String symbol,
      String setup,
      LocalDate entryDate,
      BigDecimal entryPrice,
      LocalDate exitDate,
      BigDecimal exitPrice,
      BigDecimal pnlPct,
      int barsHeld,
      String exitReason,
      BigDecimal rsRankAtEntry) {}
}
