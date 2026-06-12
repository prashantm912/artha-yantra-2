package in.arthayantra.marketdata.kite.session;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM for the token at rest (B-2 / D13): 96-bit random nonce per write, 128-bit tag.
 * The key is the 256-bit {@code ARTHA_MASTER_KEY} Docker secret (base64); plaintext tokens are
 * never logged, exported or persisted.
 */
public final class AesGcmTokenCipher {

  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  /** Ciphertext + the nonce it was sealed under (opaque blobs — equality is never used). */
  @SuppressWarnings("ArrayRecordComponent")
  public record Sealed(byte[] ciphertext, byte[] nonce) {}

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  /** Wires a 256-bit key (32 raw bytes). */
  public AesGcmTokenCipher(byte[] keyBytes) {
    if (keyBytes.length != 32) {
      throw new IllegalArgumentException(
          "ARTHA_MASTER_KEY must decode to exactly 32 bytes (got " + keyBytes.length + ")");
    }
    this.key = new SecretKeySpec(keyBytes, "AES");
  }

  /** Seals a token under a fresh random nonce. */
  public Sealed encrypt(String plaintext) {
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      return new Sealed(cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8)), nonce);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM encrypt failed", e);
    }
  }

  /** Opens a sealed token; throws when the key or the blob is wrong (GCM tag mismatch). */
  public String decrypt(byte[] ciphertext, byte[] nonce) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM decrypt failed — wrong master key or corrupt blob", e);
    }
  }
}
