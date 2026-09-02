package in.arthayantra.marketdata.health;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Durable store for outbound-reachability episodes (NEW-13).
 *
 * <p>⚠️ Every write here FAILS SOFT. This is a diagnostic recorder, and a recorder that throws into
 * its caller can take out the scheduled pass it rides on — turning an observability feature into an
 * outage of its own. The platform already rejected the opposite choice once, in review, for exactly
 * this reason. A lost row is a gap in the record; a thrown exception is a gap in the service.
 */
@Repository
public class NetworkReachabilityRepository {

  private static final Logger log = LoggerFactory.getLogger(NetworkReachabilityRepository.class);

  private final JdbcTemplate jdbc;

  public NetworkReachabilityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The currently-open episode, if any. At most one is open by construction.
   *
   * <p>⚠ TRI-STATE ON PURPOSE, and an {@code Optional} here was a real defect. "No episode is
   * open" and "I could not find out" are different facts, and collapsing them made a read failure
   * indistinguishable from a clean slate — so an observed RECOVERY was silently discarded, and the
   * next pass either closed the real episode minutes late or, if another outage had begun, merged
   * two incidents into one row. The caller must be able to decline to act.
   */
  public OpenEpisodeLookup openEpisode() {
    try {
      String key =
          jdbc
              .query(
                  "SELECT episode_key FROM network_reachability_episodes"
                      + " WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1",
                  (rs, i) -> rs.getString(1))
              .stream()
              .findFirst()
              .orElse(null);
      return new OpenEpisodeLookup(true, key);
    } catch (DataAccessException e) {
      log.warn("reachability: could not read the open episode: {}", e.toString());
      // ⚠️ UNKNOWN, not a throw and not "none". The caller makes no transition on an unknown
      // read, so a blip here can no longer be mistaken for a clean slate. Should a second open
      // ever be attempted anyway, the UNIQUE PARTIAL INDEX on (ended_at IS NULL) is the backstop —
      // NOT the UNIQUE on episode_key, which this comment used to name: each attempt mints a key
      // from its own start millisecond, so a second insert carries a different key.
      return new OpenEpisodeLookup(false, null);
    }
  }

  /**
   * The outcome of an open-episode lookup.
   *
   * @param readSucceeded whether the database answered at all; {@code false} means UNKNOWN, and the
   *     caller must not treat it as "nothing is open"
   * @param key the open episode's key, or {@code null} when none is open (only meaningful when
   *     {@code readSucceeded})
   */
  public record OpenEpisodeLookup(boolean readSucceeded, String key) {}

  /**
   * Open an episode.
   *
   * <p>⚠ TWO guards, because they stop DIFFERENT things and neither is sufficient alone.
   * {@code ON CONFLICT (episode_key)} makes a RETRY of the same open idempotent — the caller
   * re-derives the same key from the same observed start, so a retry after an ambiguous commit is a
   * no-op. It does NOT stop a SECOND, overlapping episode, because that one carries a different
   * key; the {@code WHERE NOT EXISTS} does, and the unique partial index in V061 enforces it even
   * if this predicate races. An earlier revision had only the first guard and claimed it delivered
   * both.
   *
   * @return whether the row is now present — {@code false} ONLY when the write failed. The caller
   *     needs this: if an outage RECOVERS before its opening write ever lands, nothing in the
   *     database refers to it and the incident would be lost entirely, which is the one outcome
   *     this table exists to prevent. Zero rows written because an episode is already open is
   *     SUCCESS, not failure — there is nothing left to retry.
   */
  public boolean open(
      String episodeKey,
      Instant startedAt,
      int probedCount,
      int unreachableCount,
      int quorumCount,
      String failedNames,
      String detail) {
    try {
      jdbc.update(
          "INSERT INTO network_reachability_episodes"
              + " (episode_key, started_at, probed_count, unreachable_count, quorum_count,"
              + " failed_names, detail)"
              + " SELECT ?, ?, ?, ?, ?, ?, ?"
              + " WHERE NOT EXISTS ("
              + "   SELECT 1 FROM network_reachability_episodes WHERE ended_at IS NULL)"
              + " ON CONFLICT (episode_key) DO NOTHING",
          episodeKey,
          java.sql.Timestamp.from(startedAt),
          probedCount,
          unreachableCount,
          quorumCount,
          failedNames,
          detail);
      return true;
    } catch (DataAccessException e) {
      log.warn("reachability: could not open episode {}: {}", episodeKey, e.toString());
      return false;
    }
  }

  /**
   * Close the open episode. A no-op if it was already closed or never opened.
   *
   * @return whether the episode is now closed — {@code false} ONLY when the write failed.
   *     <p>⚠ The caller needs this, and a {@code void} here is what made the gap. A swallowed
   *     close leaves the row open; if a NEW outage then begins before the next pass, the caller
   *     sees an episode already open and writes nothing, silently MERGING two incidents into one
   *     row that reads as a single long outage which never happened. Returning the outcome lets the
   *     caller retry with the instant recovery was ACTUALLY observed. Fail-soft is preserved: this
   *     still never throws into the scheduled pass.
   *     <p>Zero rows updated is SUCCESS, not failure — the row was already closed, so the desired
   *     state holds and there is nothing to retry.
   */
  public boolean close(String episodeKey, Instant endedAt) {
    try {
      jdbc.update(
          // ⚠️ GREATEST, because a retained recovery instant is REPLAYED unchanged and the row
          // carries CHECK (ended_at >= started_at). If the host clock steps backwards between the
          // start of an outage and the observation of its recovery, an unclamped retry violates
          // that constraint, fails, is retained, and is retried with the SAME invalid value
          // forever — leaving the episode permanently open and merging every later outage into
          // it. Clamping turns an unrecoverable state into a zero-length episode.
          "UPDATE network_reachability_episodes"
              + " SET ended_at = GREATEST(CAST(? AS TIMESTAMPTZ), started_at)"
              + " WHERE episode_key = ? AND ended_at IS NULL",
          java.sql.Timestamp.from(endedAt),
          episodeKey);
      return true;
    } catch (DataAccessException e) {
      log.warn("reachability: could not close episode {}: {}", episodeKey, e.toString());
      return false;
    }
  }
}
