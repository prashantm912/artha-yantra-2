package in.arthayantra.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A.6.1 masking rules: fake secrets must never survive into log output. */
class LogMaskerTest {

  @Test
  void phcHashNeverSurvives() {
    String line = "verify against $argon2id$v=19$m=19456,t=2,p=1$c2FsdHNhbHQ$aGFzaGhhc2g done";

    String masked = LogMasker.mask(line);

    assertThat(masked).doesNotContain("19456").doesNotContain("aGFzaGhhc2g");
    assertThat(masked).isEqualTo("verify against $argon2*** done");
  }

  @Test
  void secretKeyValuePairsAreMasked() {
    assertThat(LogMasker.mask("loaded api_secret=abc123xyz from env"))
        .isEqualTo("loaded api_secret=*** from env");
    assertThat(LogMasker.mask("ARTHA_MASTER_KEY=c2VjcmV0c2VjcmV0"))
        .isEqualTo("ARTHA_MASTER_KEY=***");
    assertThat(LogMasker.mask("password: hunter22")).isEqualTo("password: ***");
    assertThat(LogMasker.mask("{\"access_token\":\"xyzzy123\"}"))
        .doesNotContain("xyzzy123");
  }

  @Test
  void requestTokenStrippedFromOauthCallbackUrls() {
    String url = "callback https://app/login?request_token=TOK123abc&action=login";

    assertThat(LogMasker.mask(url))
        .doesNotContain("TOK123abc")
        .contains("request_token=***")
        .contains("action=login");
  }

  @Test
  void benignTextPassesThrough() {
    String line = "instrument NSE:RELIANCE tick seq=42 price=2950.05";

    assertThat(LogMasker.mask(line)).isEqualTo(line);
    assertThat(LogMasker.mask(null)).isNull();
    assertThat(LogMasker.mask("")).isEmpty();
  }

  @Test
  void firstLastEchoForConfigKeys() {
    assertThat(LogMasker.firstLast("tdwkabcde1t2")).isEqualTo("tdwk…t2");
    assertThat(LogMasker.firstLast("short")).isEqualTo("***");
    assertThat(LogMasker.firstLast(null)).isEqualTo("***");
  }
}
