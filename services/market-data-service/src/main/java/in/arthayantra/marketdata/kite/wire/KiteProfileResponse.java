package in.arthayantra.marketdata.kite.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code GET /user/profile} envelope: {@code {status, data}} wrapping the profile (no tokens). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KiteProfileResponse(
    @JsonProperty("status") String status, @JsonProperty("data") KiteProfile data) {}
