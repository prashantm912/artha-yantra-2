package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The C-2.14 signal-surface response shapes, typed so the contract gate can see them (ledger D3
 * slice 1).
 *
 * <p>PURE RETYPINGS of the {@code LinkedHashMap} / {@code Map.of} bodies {@link SignalsController}
 * used to assemble. Every key name, order and value type is unchanged — only the SPEC gains the
 * shape, because springdoc cannot enumerate a {@code Map<String, Object>} and so the breaking-change
 * gate was blind to any key rename on five endpoints.
 *
 * <p><b>Component order is load-bearing.</b> The list/detail body was a {@code LinkedHashMap} and
 * Jackson emits a record in canonical-constructor order, so {@link SignalDto}'s components MUST stay
 * in the controller's original put order or the serialized bytes move. Nulls are emitted, not
 * omitted (the service configures no {@code NON_NULL} inclusion), matching the map's behaviour for a
 * NULL column.
 *
 * <p>Nullability is spelled {@code types = {"x", "null"}} rather than {@code nullable = true} — the
 * latter is a SILENT NO-OP at OpenAPI 3.1. The nullable set is the DDL's: everything on {@code
 * V003__signals.sql} is NOT NULL except {@code entry_price} / {@code stop_loss} / {@code target} /
 * {@code expires_at}, and every column added later ({@code suggested_qty} V006, the {@code
 * tradeable_*} pair and {@code scalper_detail} V009, {@code exit_reason} V048) is nullable.
 */
public final class SignalViews {

  private SignalViews() {}

  /**
   * One signal as the API renders it.
   *
   * <p>Deliberately NOT {@code SignalRepository.SignalRow}: the row carries {@code minerviniDetail}
   * and {@code manasAroraDetail}, which this surface has never emitted, and its {@code exitReason}
   * sits after them rather than immediately after {@code scalperDetail}. Reusing the row would
   * therefore ADD two keys and move a third — a wire change, not a retyping.
   */
  public record SignalDto(
      long id,
      UUID strategyVersionId,
      String exchange,
      String tradingsymbol,
      String interval,
      String signalType,
      String side,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal entryPrice,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal stopLoss,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal target,
      @Schema(type = "string") BigDecimal compositeScore,
      JsonNode scoreBreakdown,
      String status,
      OffsetDateTime generatedAt,
      @Schema(types = {"string", "null"}) OffsetDateTime expiresAt,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal suggestedQty,
      @Schema(types = {"string", "null"}) String tradeableExchange,
      @Schema(types = {"string", "null"}) String tradeableTradingsymbol,
      /**
       * The E10 scalper side-channel (option leg + confluence dots); NULL on non-scalper rows
       * ({@code scalper_detail} is nullable, V009). A {@code $ref}-valued component spells its
       * nullability {@code types = {"object", "null"}} — {@code NullableRefCustomizer} rewrites that
       * into the honest {@code anyOf: [$ref, null]} union the generated TS client reads. {@code
       * scoreBreakdown} takes no annotation because {@code score_breakdown} is NOT NULL (V003).
       */
      @Schema(types = {"object", "null"}) JsonNode scalperDetail,
      /** Why an EXIT fired. NULL on ENTRY rows and on EXITs predating V048. */
      @Schema(types = {"string", "null"}) String exitReason) {}

  /** {@code GET /api/v1/signals} — paged/filtered history, echoing the bounded page window. */
  public record SignalPage(List<SignalDto> items, int limit, int offset) {}

  /** {@code GET /api/v1/signals/active} — currently takeable ENTRY calls. */
  public record SignalFeed(List<SignalDto> items) {}

  /** {@code GET /api/v1/signal-rejections} — paged rejection history, same page window. */
  public record RejectionPage(
      List<SignalRejectionRepository.RejectionRow> items, int limit, int offset) {}

  /** {@code GET /api/v1/signal-rejections/rail-counts} — the per-rail block rollup. */
  public record RailCountList(List<SignalRejectionRepository.RailCount> items) {}
}
