package in.arthayantra.marketdata.health;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Durable store for outbound-reachability observations (NEW-13).
 *
 * <p>⚠️ The write FAILS SOFT. This is a diagnostic recorder, and a recorder that throws into its
 * caller can take out the scheduled pass it rides on — turning an observability feature into an
 * outage of its own. The platform already rejected the opposite choice once, in review, for exactly
 * this reason.
 *
 * <p>⚠️ <b>There is no retry, no read-back and no state, and that is the point.</b> Five earlier
 * revisions of this class opened and closed episode rows, which required knowing what a previous
 * pass had seen; six review rounds produced thirteen findings against that machinery. A lost row
 * here costs exactly one observation out of one every five minutes, and the next pass supplies its
 * own — so the recovery path that kept going wrong is not merely simplified, it does not exist.
 */
@Repository
public class NetworkReachabilityRepository {

  private static final Logger log = LoggerFactory.getLogger(NetworkReachabilityRepository.class);

  private final JdbcTemplate jdbc;

  public NetworkReachabilityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Record one pass in which the quorum said the host's own network was down.
   *
   * @return whether the row landed. The caller does not retry on {@code false} — it logs, and the
   *     next pass observes independently. Returned rather than {@code void} so a database problem
   *     is visible in the log instead of silently thinning the record.
   */
  public boolean record(
      Instant observedAt,
      int probedCount,
      int unreachableCount,
      int quorumCount,
      String failedNames) {
    try {
      jdbc.update(
          "INSERT INTO network_reachability_observations"
              + " (observed_at, probed_count, unreachable_count, quorum_count, failed_names)"
              + " VALUES (?, ?, ?, ?, ?)",
          java.sql.Timestamp.from(observedAt),
          probedCount,
          unreachableCount,
          quorumCount,
          failedNames);
      return true;
    } catch (DataAccessException e) {
      log.warn("reachability: could not record the {} observation: {}", observedAt, e.toString());
      return false;
    }
  }
}
