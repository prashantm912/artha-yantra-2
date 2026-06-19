package in.arthayantra.marketdata.openalgo.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** One strike row of OpenAlgo {@code /optionchain}: the strike plus its CE and PE legs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlgoChainRow(
    @JsonProperty("strike") BigDecimal strike,
    @JsonProperty("ce") OpenAlgoLeg ce,
    @JsonProperty("pe") OpenAlgoLeg pe) {}
