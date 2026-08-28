package in.arthayantra.marketdata.kite.session;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.kite.AccessTokenProvider;
import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory token state over the AES-GCM row (B-2). The plaintext token lives ONLY here; expiry
 * clears it so every outbound adapter short-circuits with {@code KITE_TOKEN_EXPIRED} (re-login,
 * not retry, is the cure). Restart-reload decrypts the persisted row — no re-login within the
 * trading day.
 */
public class KiteSessionStore implements AccessTokenProvider {

  /** Session state for the status surface + Redis key (COMMON §3 values). */
  public enum State {
    DISCONNECTED,
    CONNECTED,
    TOKEN_EXPIRED,
    ERROR
  }

  private static final Logger log = LoggerFactory.getLogger(KiteSessionStore.class);

  private final KiteSessionRepository repository;
  private final AesGcmTokenCipher cipher;
  private final AtomicReference<String> token = new AtomicReference<>();
  private volatile State state = State.DISCONNECTED;
  private volatile String kiteUserId;
  private volatile OffsetDateTime encryptedAt;
  private volatile OffsetDateTime lastValidatedAt;

  private final Clock clock;

  /**
   * Wires persistence + crypto.
   *
   * <p>The {@link Clock} exists so the restore can ask whether a persisted token is already
   * dead. Injected rather than {@code now()} so the boundary is testable at all.
   */
  public KiteSessionStore(
      KiteSessionRepository repository, AesGcmTokenCipher cipher, Clock clock) {
    this.repository = repository;
    this.cipher = cipher;
    this.clock = clock;
  }

  /** Decrypt-and-resume on startup; a wrong master key degrades to DISCONNECTED, never crashes. */
  public void loadFromDatabase() {
    repository
        .find()
        .ifPresent(
            row -> {
              try {
                token.set(cipher.decrypt(row.tokenCiphertext(), row.nonce()));
                kiteUserId = row.kiteUserId();
                encryptedAt = row.encryptedAt();
                lastValidatedAt = row.lastValidatedAt();
                // ⚠️ Kite tokens die ~06:00 IST, and until 2026-08-28 this restored YESTERDAY'S
                // token as tentatively CONNECTED regardless. The cost was measured that
                // morning: every scheduled Kite consumer then made authenticated calls with a
                // dead token, took ten straight 403 TokenException, and opened the SHARED
                // kite-rest breaker at 08:39:58 -- browning out unrelated consumers until the
                // owner logged in at 08:46.
                //
                // The store already KNEW: tokenValidUntil() computes the death time and was
                // used for the status surface but never for this decision. Consulting it makes
                // the adapters short-circuit LOCALLY (they gate on token presence), so a dead
                // token costs zero wire calls.
                //
                // ⚠️ "and zero breaker pressure and zero rate budget" was NOT true when this
                // was written, and cross-vendor review caught it: LiveInstrumentDumpGateway
                // resolved the token INSIDE the executor supplier, so a tokenless boot still
                // burned the 1-per-30-minute DUMP permit and recorded a kite-rest failure.
                // Fixed in the same change. If a future gateway resolves its token inside
                // executor.execute(...), this paragraph quietly becomes false again.
                OffsetDateTime validUntil = tokenValidUntil();
                if (validUntil != null && !OffsetDateTime.now(clock).isBefore(validUntil)) {
                  markExpired();
                  // encrypted_at is carried on BOTH paths deliberately: it answers the only
                  // operational question this log exists for -- WHICH token was found -- and an
                  // expired restore needs that answer at least as much as a resumed one. The
                  // Kite user id stays out of both, for the reason below.
                  log.info(
                      "kite session restore: persisted token from {} expired at {} — NOT"
                          + " resumed; a fresh login is required",
                      encryptedAt,
                      validUntil);
                  return;
                }
                state = State.CONNECTED; // tentative until the next health probe

                // ⚠️ The Kite user id is DELIBERATELY not logged (cross-vendor review,
                // Critical 3, 2026-08-26). It is half of the interactive broker-account credential,
                // and the TOTP auto-login path now exercises this restore on every morning boot --
                // so a line that was low-traffic became routine. encrypted_at alone answers the
                // only operational question this log is for ("which token came back").
                log.info("kite session restored from store (encrypted_at={})", row.encryptedAt());
              } catch (IllegalStateException wrongKey) {
                log.warn(
                    "stored kite token cannot be decrypted (rotated ARTHA_MASTER_KEY?) — "
                        + "re-login required: {}",
                    wrongKey.getMessage());
              }
            });
  }

  /** Encrypt-and-persist a fresh token (login / manual exchange). */
  public void store(String accessToken, String userId) {
    AesGcmTokenCipher.Sealed sealed = cipher.encrypt(accessToken);
    repository.save(sealed.ciphertext(), sealed.nonce(), userId);
    token.set(accessToken);
    kiteUserId = userId;
    OffsetDateTime now = OffsetDateTime.now(Ist.ZONE);
    encryptedAt = now;
    lastValidatedAt = now;
    state = State.CONNECTED;
  }

  /** Probe says LIVE — stamp it. */
  public void markValid() {
    state = State.CONNECTED;
    lastValidatedAt = OffsetDateTime.now(Ist.ZONE);
    repository.touchValidated();
  }

  /** Probe says 401/403 — clear the in-memory token so outbound calls short-circuit. */
  public void markExpired() {
    token.set(null);
    state = State.TOKEN_EXPIRED;
  }

  /** Probe errored (network/Kite outage) — keep the token, flag the state. */
  public void markError() {
    state = State.ERROR;
  }

  /** Operator invalidation — token gone everywhere. */
  public void clear() {
    repository.delete();
    token.set(null);
    kiteUserId = null;
    encryptedAt = null;
    lastValidatedAt = null;
    state = State.DISCONNECTED;
  }

  @Override
  public Optional<String> currentToken() {
    return Optional.ofNullable(token.get());
  }

  /** Current lifecycle state. */
  public State state() {
    return state;
  }

  /** The Kite user behind the stored token, when known. */
  public String kiteUserId() {
    return kiteUserId;
  }

  /** Last successful health-probe stamp. */
  public OffsetDateTime lastValidatedAt() {
    return lastValidatedAt;
  }

  /** Kite tokens die ~06:00 IST the morning after issuance (B-2). */
  public OffsetDateTime tokenValidUntil() {
    OffsetDateTime issued = encryptedAt;
    if (issued == null) {
      return null;
    }
    OffsetDateTime issuedIst = issued.toInstant().atZone(Ist.ZONE).toOffsetDateTime();
    OffsetDateTime sixAm = issuedIst.toLocalDate().atTime(LocalTime.of(6, 0)).atOffset(Ist.OFFSET);
    return issuedIst.isBefore(sixAm) ? sixAm : sixAm.plusDays(1);
  }
}
