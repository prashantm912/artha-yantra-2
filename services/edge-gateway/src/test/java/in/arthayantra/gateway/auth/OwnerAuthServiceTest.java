package in.arthayantra.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pins the A.2.3 Argon2id parameters: m=19456 KiB, t=2, p=1. */
class OwnerAuthServiceTest {

  @Test
  void encoderEmitsPinnedArgon2idParameters() {
    String phc = OwnerAuthService.ENCODER.encode("s3cret");

    assertThat(phc).startsWith("$argon2id$").contains("m=19456,t=2,p=1");
  }

  @Test
  void verifiesAgainstConfiguredHash() {
    String phc = OwnerAuthService.ENCODER.encode("correct horse");
    OwnerAuthService service = new OwnerAuthService(phc);

    assertThat(service.isConfigured()).isTrue();
    assertThat(service.verify("correct horse")).isTrue();
    assertThat(service.verify("wrong")).isFalse();
    assertThat(service.verify(null)).isFalse();
  }

  @Test
  void unconfiguredHashNeverVerifies() {
    OwnerAuthService service = new OwnerAuthService("  ");

    assertThat(service.isConfigured()).isFalse();
    assertThat(service.verify("anything")).isFalse();
  }
}
