package in.arthayantra.marketdata.kite.session;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to the single-row {@code kite_session} table (Phase 12 / B-2). */
@Repository
public class KiteSessionRepository {

  /** The persisted row (crypto blobs are opaque — record equality is never used). */
  @SuppressWarnings("ArrayRecordComponent")
  public record SessionRow(
      byte[] tokenCiphertext,
      byte[] nonce,
      String kiteUserId,
      OffsetDateTime encryptedAt,
      OffsetDateTime lastValidatedAt) {}

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public KiteSessionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Upserts the singleton row (a fresh login replaces any previous token). */
  public void save(byte[] ciphertext, byte[] nonce, String kiteUserId) {
    jdbc.update(
        """
        INSERT INTO kite_session (id, token_ciphertext, nonce, kite_user_id, encrypted_at, last_validated_at)
        VALUES (1, ?, ?, ?, now(), now())
        ON CONFLICT (id) DO UPDATE SET
          token_ciphertext = EXCLUDED.token_ciphertext,
          nonce = EXCLUDED.nonce,
          kite_user_id = EXCLUDED.kite_user_id,
          encrypted_at = now(),
          last_validated_at = now()
        """,
        ciphertext, nonce, kiteUserId);
  }

  /** The stored row, when a token has been persisted. */
  public Optional<SessionRow> find() {
    List<SessionRow> rows =
        jdbc.query(
            "SELECT token_ciphertext, nonce, kite_user_id, encrypted_at, last_validated_at"
                + " FROM kite_session WHERE id = 1",
            (rs, n) ->
                new SessionRow(
                    rs.getBytes("token_ciphertext"),
                    rs.getBytes("nonce"),
                    rs.getString("kite_user_id"),
                    rs.getObject("encrypted_at", OffsetDateTime.class),
                    rs.getObject("last_validated_at", OffsetDateTime.class)));
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** Stamps a successful health-probe validation. */
  public void touchValidated() {
    jdbc.update("UPDATE kite_session SET last_validated_at = now() WHERE id = 1");
  }

  /** Removes the stored token (logout / invalidation). */
  public void delete() {
    jdbc.update("DELETE FROM kite_session WHERE id = 1");
  }
}
