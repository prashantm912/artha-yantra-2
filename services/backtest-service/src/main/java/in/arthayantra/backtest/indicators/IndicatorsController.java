package in.arthayantra.backtest.indicators;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 40B (A13): the chart indicator surface. A registry list plus a series endpoint, both
 * driven by the strategy-engine {@link in.arthayantra.strategyengine.indicators.IndicatorRegistry}
 * — adding an indicator needs no per-indicator endpoint code. Lives in backtest-service (already
 * embeds the engine JAR + holds marketdata read grants); routed at {@code /api/v1/indicators/**}.
 */
@RestController
@RequestMapping("/api/v1/indicators")
public class IndicatorsController {

  private final IndicatorSeriesService service;
  private final ObjectMapper paramsMapper = new ObjectMapper();

  /** Wires the indicator-series service. */
  public IndicatorsController(IndicatorSeriesService service) {
    this.service = service;
  }

  /** The registry list: id, label, params+defaults, output series, render + pane hints. */
  @GetMapping
  public IndicatorRegistry list() {
    return new IndicatorRegistry(service.registry());
  }

  /**
   * Named value series for an indicator over a candle window. {@code symbol} is canonical
   * {@code EXCHANGE:TRADINGSYMBOL}; {@code params} is URL-encoded JSON (defaults applied when absent).
   */
  @GetMapping("/{id}/series")
  public IndicatorSeriesResponse series(
      @PathVariable String id,
      @RequestParam String symbol,
      @RequestParam String interval,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
      @RequestParam(required = false) String params) {
    int sep = symbol.indexOf(':');
    if (sep < 0) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "symbol must be EXCHANGE:TRADINGSYMBOL");
    }
    String exchange = symbol.substring(0, sep);
    String tradingsymbol = symbol.substring(sep + 1);
    return new IndicatorSeriesResponse(
        service.series(id, exchange, tradingsymbol, interval, from, to, parseParams(params)));
  }

  private Map<String, Object> parseParams(String params) {
    if (params == null || params.isBlank()) {
      return Map.of();
    }
    try {
      return paramsMapper.readValue(params, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "params must be a JSON object");
    }
  }
}
