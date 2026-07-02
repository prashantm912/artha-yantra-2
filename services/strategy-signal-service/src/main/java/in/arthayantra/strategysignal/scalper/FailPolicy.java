package in.arthayantra.strategysignal.scalper;

/**
 * A confluence-gate rail's declared missing-data polarity (audit P2). Every rail must state what
 * it does when its input is null/unwarmed/unavailable — the policy used to live only in scattered
 * prose comments across ~900 lines, so a new rail could silently pick the wrong default: fail-open
 * where the data mattered admits live entries on missing confirmation; fail-closed on a flaky feed
 * silently suppresses every entry. {@link RailPolicies} is the registry; recording a rail whose
 * name is not registered throws, and the table test pins the registry to the source.
 */
public enum FailPolicy {
  /** Missing input degrades to PASS — absence of this signal can never block an entry. */
  FAIL_OPEN,
  /** Missing input BLOCKS — no confirmation, no trade (protective rails, hard preconditions). */
  FAIL_CLOSED
}
