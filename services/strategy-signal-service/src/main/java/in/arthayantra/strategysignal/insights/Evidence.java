package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * One evidence pointer (INT design §2.1 / §4.3): a labelled value plus, optionally, the navigable
 * source that produced it and a domain ref. Evidence is MANDATORY and non-empty on every insight —
 * "trust but verify" is one click, and every claim in the {@code explanation} has a matching entry.
 * Serialized into the {@code insights.evidence} JSONB array; nulls are dropped so the stored shape
 * stays tidy and byte-stable (golden determinism, §10.1).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Evidence(String label, String value, Source source, Map<String, Object> ref) {

  /** The navigable source of an evidence value (§3.4 explain contract). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Source(String endpoint, Map<String, Object> params, String asOf) {}

  /** A bare label→value evidence (no source, no ref). */
  public static Evidence of(String label, String value) {
    return new Evidence(label, value, null, null);
  }

  /** A label→value with a navigable source endpoint + asOf. */
  public static Evidence sourced(String label, String value, String endpoint, String asOf) {
    return new Evidence(label, value, new Source(endpoint, null, asOf), null);
  }

  /** A label→value with a navigable source endpoint + params + asOf. */
  public static Evidence sourced(
      String label, String value, String endpoint, Map<String, Object> params, String asOf) {
    return new Evidence(label, value, new Source(endpoint, params, asOf), null);
  }
}
