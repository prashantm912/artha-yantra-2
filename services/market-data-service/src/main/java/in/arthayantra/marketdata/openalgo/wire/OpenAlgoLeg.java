package in.arthayantra.marketdata.openalgo.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * One side (CE or PE) of a strike in OpenAlgo {@code POST /api/v1/optionchain}
 * (docs.openalgo.in data-api/optionchain). {@code oi} is the per-strike open-interest the
 * irreplaceable {@code options_chain_snapshots} capture needs — the load-bearing field this whole
 * integration exists to route (plan §4.5 / M0). {@code oi} arrives as a decimal and is narrowed to
 * {@code Long} on mapping.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlgoLeg(
    @JsonProperty("symbol") String symbol,
    @JsonProperty("label") String label,
    @JsonProperty("ltp") BigDecimal ltp,
    @JsonProperty("bid") BigDecimal bid,
    @JsonProperty("ask") BigDecimal ask,
    @JsonProperty("bid_qty") Long bidQty,
    @JsonProperty("ask_qty") Long askQty,
    @JsonProperty("open") BigDecimal open,
    @JsonProperty("high") BigDecimal high,
    @JsonProperty("low") BigDecimal low,
    @JsonProperty("prev_close") BigDecimal prevClose,
    @JsonProperty("volume") Long volume,
    @JsonProperty("oi") BigDecimal oi,
    @JsonProperty("lotsize") Integer lotSize,
    @JsonProperty("tick_size") BigDecimal tickSize) {}
