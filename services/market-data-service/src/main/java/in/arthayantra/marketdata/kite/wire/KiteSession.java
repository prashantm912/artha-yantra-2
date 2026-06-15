package in.arthayantra.marketdata.kite.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The {@code POST /session/token} login payload — the full profile plus the session-only tokens
 * ({@code access_token}, {@code public_token}, {@code refresh_token}, {@code enctoken},
 * {@code api_key}, {@code login_time}, {@code silo}). Only {@code access_token} + {@code user_id}
 * are promoted to the domain {@code TokenSession}; the rest are mirrored for reference.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KiteSession(
    @JsonProperty("user_id") String userId,
    @JsonProperty("user_name") String userName,
    @JsonProperty("user_shortname") String userShortname,
    @JsonProperty("email") String email,
    @JsonProperty("user_type") String userType,
    @JsonProperty("broker") String broker,
    @JsonProperty("exchanges") List<String> exchanges,
    @JsonProperty("products") List<String> products,
    @JsonProperty("order_types") List<String> orderTypes,
    @JsonProperty("avatar_url") String avatarUrl,
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("public_token") String publicToken,
    @JsonProperty("refresh_token") String refreshToken,
    @JsonProperty("enctoken") String enctoken,
    @JsonProperty("api_key") String apiKey,
    @JsonProperty("login_time") String loginTime,
    @JsonProperty("silo") String silo,
    @JsonProperty("meta") KiteMeta meta) {}
