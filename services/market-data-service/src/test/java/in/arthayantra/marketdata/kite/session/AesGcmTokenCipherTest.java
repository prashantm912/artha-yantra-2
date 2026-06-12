package in.arthayantra.marketdata.kite.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Phase-12 crypto: roundtrip, wrong-key failure, per-write nonce freshness. */
class AesGcmTokenCipherTest {

  private static byte[] key(int seed) {
    byte[] key = new byte[32];
    for (int i = 0; i < key.length; i++) {
      key[i] = (byte) (seed + i);
    }
    return key;
  }

  @Test
  void roundtripRecoversThePlaintext() {
    AesGcmTokenCipher cipher = new AesGcmTokenCipher(key(1));

    AesGcmTokenCipher.Sealed sealed = cipher.encrypt("fake-access-token-123");

    assertThat(new String(sealed.ciphertext(), java.nio.charset.StandardCharsets.UTF_8))
        .doesNotContain("fake-access-token");
    assertThat(cipher.decrypt(sealed.ciphertext(), sealed.nonce()))
        .isEqualTo("fake-access-token-123");
  }

  @Test
  void wrongKeyFailsTheGcmTag() {
    AesGcmTokenCipher.Sealed sealed = new AesGcmTokenCipher(key(1)).encrypt("secret-token");

    assertThatThrownBy(() -> new AesGcmTokenCipher(key(2)).decrypt(sealed.ciphertext(), sealed.nonce()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("wrong master key");
  }

  @Test
  void everyWriteGetsFreshNonce() {
    AesGcmTokenCipher cipher = new AesGcmTokenCipher(key(1));

    AesGcmTokenCipher.Sealed first = cipher.encrypt("same-token");
    AesGcmTokenCipher.Sealed second = cipher.encrypt("same-token");

    assertThat(first.nonce()).isNotEqualTo(second.nonce());
    assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
  }

  @Test
  void rejectsNon256BitKeys() {
    assertThatThrownBy(() -> new AesGcmTokenCipher(new byte[16]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32 bytes");
  }
}
