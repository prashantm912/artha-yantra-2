package in.arthayantra.strategysignal.insights;

import java.util.List;
import java.util.Optional;

/**
 * A point-in-time trust reading across data families (INT design §7). Composed by
 * {@link TrustService} from the P1 rails (ingest ledger + live-capture health) and rendered as the
 * {@code /trust-summary} strip. The DATA_TRUST generator emits on DEGRADED/BLOCKED families; the
 * SIGNAL_PRIORITY trust cap reads the live-capture family.
 */
public record TrustSnapshot(String asOf, List<FamilyTrust> families) {

  /** One data family's trust: state + human reasons + last-good asOf (INT design §7.2/§7.3). */
  public record FamilyTrust(String family, DataTrust state, List<String> reasons, String asOf) {}

  /** The named family, if present. */
  public Optional<FamilyTrust> family(String name) {
    return families.stream().filter(f -> f.family().equals(name)).findFirst();
  }
}
