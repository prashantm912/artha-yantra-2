package in.arthayantra.marketdata.openalgo.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * The {@code data} block of OpenAlgo {@code POST /api/v1/quotes} (docs.openalgo.in data-api/quotes;
 * verified against {@code openalgo/services/quotes_service.py} + {@code broker/<plugin>/api/data.py}). The
 * quote ALWAYS carries {@code oi} ({@code 0} for cash/index, real open-interest for F&O — emitted
 * unconditionally server-side). Index quotes omit meaningful bid/ask. Prices are exact decimals.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlgoQuote(
    @JsonProperty("ltp") BigDecimal ltp,
    @JsonProperty("bid") BigDecimal bid,
    @JsonProperty("ask") BigDecimal ask,
    @JsonProperty("open") BigDecimal open,
    @JsonProperty("high") BigDecimal high,
    @JsonProperty("low") BigDecimal low,
    @JsonProperty("prev_close") BigDecimal prevClose,
    @JsonProperty("volume") Long volume,
    @JsonProperty("oi") BigDecimal oi) {}
