package in.arthayantra.marketdata.kite.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * {@code GET /user/profile} — the common user fields, identical to the session payload but with
 * <b>no</b> session tokens or {@code login_time} (Kite explicitly omits tokens from the profile).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KiteProfile(
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
    @JsonProperty("meta") KiteMeta meta) {}
