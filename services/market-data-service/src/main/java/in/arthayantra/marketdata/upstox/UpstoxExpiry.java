package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Wire mirror of Upstox {@code GET /v2/expired-instruments/expiries} — the list of expiry dates
 * ({@code YYYY-MM-DD}) for an underlying. {@code @JsonIgnoreProperties(ignoreUnknown=true)} so a
 * field Upstox adds never crashes the feed (the kite/wire convention).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxExpiry(String status, List<String> data) {}
