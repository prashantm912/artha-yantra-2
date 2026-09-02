package in.arthayantra.marketdata.health;

import java.time.Instant;
import java.util.Optional;
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

  /** The currently-open episode key, if one exists. At most one is open by construction. */
  public Optional<String> openEpisodeKey() {
    try {
      return jdbc
          .query(
              "SELECT episode_key FROM network_reachability_episodes"
                  + " WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1",
              (rs, i) -> rs.getString(1))
          .stream()
          .findFirst();
    } catch (DataAccessException e) {
      log.warn("reachability: could not read the open episode: {}", e.toString());
      // ⚠️ EMPTY, not a throw — but note this is the one fail-soft with a visible consequence: a
      // DB blip here makes the next pass believe no episode is open, so it may open a second one.
      // The UNIQUE key on episode_key is what stops that becoming two overlapping open rows.
      return Optional.empty();
    }
  }

  /**
   * Open an episode. Keyed so a retry after an ambiguous commit cannot double-open — the UNIQUE
   * constraint makes the second attempt a no-op rather than a duplicate.
   */
  public void open(
      String episodeKey,
      Instant startedAt,
      int probedCount,
      int unreachableCount,
      String failedNames,
      String detail) {
    try {
      jdbc.update(
          "INSERT INTO network_reachability_episodes"
              + " (episode_key, started_at, probed_count, unreachable_count, failed_names, detail)"
              + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (episode_key) DO NOTHING",
          episodeKey,
          java.sql.Timestamp.from(startedAt),
          probedCount,
          unreachableCount,
          failedNames,
          detail);
    } catch (DataAccessException e) {
      log.warn("reachability: could not open episode {}: {}", episodeKey, e.toString());
    }
  }

  /** Close the open episode. A no-op if it was already closed or never opened. */
  public void close(String episodeKey, Instant endedAt) {
    try {
      jdbc.update(
          "UPDATE network_reachability_episodes SET ended_at = ?"
              + " WHERE episode_key = ? AND ended_at IS NULL",
          java.sql.Timestamp.from(endedAt),
          episodeKey);
    } catch (DataAccessException e) {
      log.warn("reachability: could not close episode {}: {}", episodeKey, e.toString());
    }
  }
}
