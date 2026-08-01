package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/** Typed response views for the results and summary endpoints. */
public final class BacktestViews {

  private BacktestViews() {}

  /** The paged trade envelope for a completed run. */
  public record BacktestTradePage(List<BacktestTradeItem> items, int limit, int offset) {}

  /**
   * One trade row as emitted by the results endpoint.
   *
   * <p>Every {@code BigDecimal} carries an explicit {@code type = "string"}: the platform mapper
   * ({@code ArthaJacksonAutoConfiguration}) routes BigDecimal through {@code ToStringSerializer}, so
   * these are decimal STRINGS on the wire while springdoc would otherwise infer {@code number}.
   * {@code types} alone would not fix it — it UNIONS with the inferred type, so a nullable decimal
   * needs BOTH {@code type = "string"} (replaces) and {@code types = {"string", "null"}} (widens).
   */
  public record BacktestTradeItem(
      int seq,
      String side,
      long qty,
      String entryTs,
      @Schema(type = "string") BigDecimal entryPrice,
      @Schema(types = {"string", "null"}) String exitTs,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal exitPrice,
      @Schema(type = "string") BigDecimal pnl,
      @Schema(type = "string") BigDecimal pnlPct,
      @Schema(types = {"string", "null"}) String exitReason,
      int barsHeld,
      @Schema(types = {"string", "null"}) String touchBasis,
      @Schema(types = {"object", "null"}) JsonNode contributions,
      @Schema(types = {"string", "null"}) String exchange,
      @Schema(types = {"string", "null"}) String tradingsymbol,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal stopLoss,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal takeProfit) {}

  /** The latest-run summary envelope for requested strategy versions. */
  public record BacktestSummaryPage(List<BacktestSummaryItem> items) {}

  /** One latest-run summary row as emitted by the results summary endpoint. */
  public record BacktestSummaryItem(
      @Schema(types = {"string", "null"}) String strategyVersionId,
      String runId,
      String sharpe,
      String totalReturn,
      String maxDrawdown,
      String completedAt,
      List<String> equity) {}
}
