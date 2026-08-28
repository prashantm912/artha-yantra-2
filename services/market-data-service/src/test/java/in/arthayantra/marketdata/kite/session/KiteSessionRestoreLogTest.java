package in.arthayantra.marketdata.kite.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The Kite user id must never reach a log line.
 *
 * <p>⚠️ <b>Cross-vendor review, Critical 3, 2026-08-26.</b> {@code KiteSessionStore.loadFromDatabase}
 * logged {@code user={}} at INFO on every restore. The leak PRE-DATES the TOTP auto-login work and
 * is not in that package at all — but the auto-login path exercises this restore on every morning
 * boot, so a line that was occasional became routine, and the user id is one half of the
 * interactive broker-account credential that feature now stores on this box. That is precisely the
 * boundary violation a diff-scoped review cannot see: nothing in the auto-login diff touches this
 * file.
 *
 * <p>Asserted on the RENDERED message, not on the format string, because the format string alone
 * cannot tell you what an argument expands to.
 */
class KiteSessionRestoreLogTest {

  private static final String SENSITIVE_USER_ID = "PLACEHOLDER-USER-ID";

  private ListAppender<ILoggingEvent> logs;
  private Logger storeLogger;

  @BeforeEach
  void attachAppender() {
    logs = new ListAppender<>();
    logs.start();
    storeLogger = (Logger) LoggerFactory.getLogger(KiteSessionStore.class);
    storeLogger.addAppender(logs);
    storeLogger.setLevel(Level.DEBUG);
  }

  @AfterEach
  void detachAppender() {
    storeLogger.detachAppender(logs);
  }

  @Test
  @DisplayName("⚠️ restoring a session logs the timestamp but NEVER the Kite user id")
  void theRestoreLogDoesNotCarryTheUserId() {
    KiteSessionRepository repository = mock(KiteSessionRepository.class);
    AesGcmTokenCipher cipher = mock(AesGcmTokenCipher.class);
    OffsetDateTime encryptedAt = OffsetDateTime.parse("2026-08-26T08:05:00+05:30");
    when(repository.find())
        .thenReturn(
            Optional.of(
                new KiteSessionRepository.SessionRow(
                    new byte[] {1, 2, 3},
                    new byte[] {4, 5, 6},
                    SENSITIVE_USER_ID,
                    encryptedAt,
                    encryptedAt)));
    when(cipher.decrypt(new byte[] {1, 2, 3}, new byte[] {4, 5, 6})).thenReturn("token-value");

    // ⚠️ A FIXED clock, not systemUTC(). The restore now refuses a token past its ~06:00 IST
    // expiry, so a wall-clock store would make this test pass on 2026-08-26 and fail every day
    // after -- a time bomb, and one this test would report as a LEAK regression rather than a
    // clock problem. Pinned an hour after issuance so it exercises the RESUMED path, which is
    // the one whose log line this test is about.
    java.time.Clock justAfterIssuance =
        java.time.Clock.fixed(
            java.time.OffsetDateTime.parse("2026-08-26T09:05:00+05:30").toInstant(),
            in.arthayantra.common.web.time.Ist.ZONE);
    new KiteSessionStore(repository, cipher, justAfterIssuance).loadFromDatabase();

    assertThat(logs.list)
        .as("the restore must still be observable — a silent restore is its own problem")
        .isNotEmpty();
    for (ILoggingEvent event : logs.list) {
      assertThat(event.getFormattedMessage())
          .as("the Kite user id is half of the interactive account credential; it must not be logged")
          .doesNotContain(SENSITIVE_USER_ID);
    }
    assertThat(logs.list.get(0).getFormattedMessage())
        .as("encrypted_at is what the operator actually needs from this line")
        .contains("2026-08-26");
  }
}
