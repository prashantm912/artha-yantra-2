package in.arthayantra.gateway.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * One human, one credential (A.2.3 / D13): verifies the owner password against the Argon2id PHC
 * string from {@code ARTHA_OWNER_PASSWORD_HASH}. No user table exists. Parameters are pinned:
 * saltLength=16, hashLength=32, parallelism=1, memory=19456 KiB, iterations=2.
 */
@Service
public class OwnerAuthService {

  /** The pinned A.2.3 encoder — tools/hash-password (CD-13) emits compatible PHC strings. */
  public static final Argon2PasswordEncoder ENCODER = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);

  private final String ownerPasswordHash;

  public OwnerAuthService(@Value("${artha.owner-password-hash:}") String ownerPasswordHash) {
    this.ownerPasswordHash = ownerPasswordHash == null ? "" : ownerPasswordHash.trim();
  }

  /** True when a hash is configured at all — login is impossible (never open) without one. */
  public boolean isConfigured() {
    return !ownerPasswordHash.isEmpty();
  }

  /**
   * Argon2id verification — CPU/memory-bound by design (~19 MiB); callers must run it off the
   * event loop ({@code Schedulers.boundedElastic()}).
   */
  public boolean verify(String rawPassword) {
    return isConfigured() && rawPassword != null && ENCODER.matches(rawPassword, ownerPasswordHash);
  }
}
