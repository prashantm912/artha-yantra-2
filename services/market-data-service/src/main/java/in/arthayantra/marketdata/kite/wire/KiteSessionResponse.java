package in.arthayantra.marketdata.kite.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code POST /session/token} envelope: {@code {status, data}} wrapping the logged-in session. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KiteSessionResponse(
    @JsonProperty("status") String status, @JsonProperty("data") KiteSession data) {}
