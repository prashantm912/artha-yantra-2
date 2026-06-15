package in.arthayantra.marketdata.kite.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The {@code meta} block shared by the session and profile payloads. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KiteMeta(@JsonProperty("demat_consent") String dematConsent) {}
