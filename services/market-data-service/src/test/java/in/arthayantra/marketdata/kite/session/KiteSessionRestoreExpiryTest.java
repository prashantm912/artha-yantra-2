package in.arthayantra.marketdata.kite.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.time.Ist;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A persisted Kite token that has already died must NOT come back as CONNECTED.
 *
 * <p><b>The measured defect, 2026-08-28.</b> The restore set {@code State.CONNECTED} for whatever
 * token was in the row, so after an overnight restart every scheduled Kite consumer made
 * authenticated calls with YESTERDAY's token. Ten straight {@code 403 TokenException} at 08:39:5x
 * opened the SHARED {@code kite-rest} breaker and browned out unrelated consumers until the owner
 * logged in at 08:46.
 *
 * <p><b>The store already knew.</b> {@code tokenValidUntil()} computes the ~06:00 IST death time and
 * was used for the status surface — but never consulted by the restore. This is not new knowledge,
 * it is knowledge that was not being used.
 *
 * <p><b>Why it matters that the token is CLEARED, not just re-labelled.</b> The outbound adapters
 * gate on token PRESENCE ({@code currentToken().orElseThrow()}), not on the state enum. So clearing
 * it is what makes a dead token cost zero wire calls — setting the state alone would have left
 * the 403 storm exactly as it was.
 *
 * <p>⚠️ The stronger claim ("and zero breaker pressure and zero rate budget") was FALSE when
 * first written here, and cross-vendor review caught it. It held for the quote and historical
 * gateways, which resolve the token first, and NOT for the dump gateway, which resolved it
 * inside the executor supplier and so still burned a scarce permit. Fixed alongside; see
 * {@code LiveInstrumentDumpGatewayTest.aDumpWithNoSessionNeverEntersTheExecutor}. Generalising
 * from two of three call sites is what produced the wrong claim.
 */
class KiteSessionRestoreExpiryTest {

  /** Kite tokens die at 06:00 IST the morning after issuance. */
  private static Instant ist(int year, int month, int day, int hour, int minute) {
    return LocalDateTime.of(year, month, day, hour, minute).atZone(Ist.ZONE).toInstant();
  }

  private KiteSessionStore restoredWith(Instant encryptedAt, Instant now) {
    KiteSessionRepository repository = mock(KiteSessionRepository.class);
    AesGcmTokenCipher cipher = mock(AesGcmTokenCipher.class);
    OffsetDateTime stamp = encryptedAt.atZone(Ist.ZONE).toOffsetDateTime();
    when(repository.find())
        .thenReturn(
            Optional.of(
                new KiteSessionRepository.SessionRow(
                    new byte[] {1, 2, 3},
                    new byte[] {4, 5, 6},
                    "placeholder-user",
                    stamp,
                    stamp)));
    when(cipher.decrypt(new byte[] {1, 2, 3}, new byte[] {4, 5, 6})).thenReturn("token-value");

    KiteSessionStore store = new KiteSessionStore(repository, cipher, Clock.fixed(now, Ist.ZONE));
    store.loadFromDatabase();
    return store;
  }

  @Test
  void yesterdaysTokenIsNotResumed() {
    // Issued 08:45 on the 27th, so it dies at 06:00 on the 28th. Restored at 08:39 on the 28th --
    // the exact shape of the measured incident.
    KiteSessionStore store =
        restoredWith(ist(2026, 8, 27, 8, 45), ist(2026, 8, 28, 8, 39));

    assertThat(store.state())
        .as("a dead token restored as CONNECTED is what produced the 403 storm")
        .isEqualTo(KiteSessionStore.State.TOKEN_EXPIRED);
    assertThat(store.currentToken())
        .as(
            "the adapters gate on token PRESENCE, so it must be CLEARED — a state change alone"
                + " would still let every consumer hit the wire with a dead token")
        .isEmpty();
  }

  @Test
  void aTokenIssuedThisMorningIsStillResumed() {
    // Issued 08:45, restarted 10:30 the SAME day: still alive until 06:00 tomorrow. The fix must
    // not turn an ordinary mid-day restart into a forced re-login.
    KiteSessionStore store =
        restoredWith(ist(2026, 8, 28, 8, 45), ist(2026, 8, 28, 10, 30));

    assertThat(store.state()).isEqualTo(KiteSessionStore.State.CONNECTED);
    assertThat(store.currentToken()).isPresent();
  }

  /**
   * The boundary is the death moment itself, and it is inclusive: at exactly 06:00 the token is
   * gone. An off-by-one here fails in the WORSE direction — resuming a dead token is precisely the
   * defect — so it is pinned rather than left to the reader.
   */
  @Test
  void theBoundaryAtSixAmIsExclusiveOfResuming() {
    OffsetDateTime sixAm =
        LocalDateTime.of(2026, 8, 28, 6, 0).atZone(Ist.ZONE).toOffsetDateTime();

    KiteSessionStore atDeath =
        restoredWith(ist(2026, 8, 27, 8, 45), sixAm.toInstant());
    assertThat(atDeath.state()).isEqualTo(KiteSessionStore.State.TOKEN_EXPIRED);

    KiteSessionStore aMinuteBefore =
        restoredWith(ist(2026, 8, 27, 8, 45), sixAm.minusMinutes(1).toInstant());
    assertThat(aMinuteBefore.state()).isEqualTo(KiteSessionStore.State.CONNECTED);
  }

  /** A token issued before 06:00 dies at 06:00 the SAME morning, not the next one. */
  @Test
  void aTokenIssuedBeforeSixAmDiesTheSameMorning() {
    KiteSessionStore store =
        restoredWith(ist(2026, 8, 28, 5, 30), ist(2026, 8, 28, 7, 0));

    assertThat(store.state()).isEqualTo(KiteSessionStore.State.TOKEN_EXPIRED);
  }

  @Test
  void theTokenValidUntilItSelfIsUnchanged() {
    KiteSessionStore store =
        restoredWith(ist(2026, 8, 28, 8, 45), ist(2026, 8, 28, 10, 30));

    assertThat(store.tokenValidUntil().toInstant())
        .isEqualTo(
            LocalDateTime.of(2026, 8, 29, 6, 0).atZone(Ist.ZONE).toInstant());
    assertThat(store.tokenValidUntil().toLocalTime()).isEqualTo(LocalTime.of(6, 0));
  }
}
