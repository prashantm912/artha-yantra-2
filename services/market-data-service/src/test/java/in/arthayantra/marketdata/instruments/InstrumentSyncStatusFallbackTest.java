package in.arthayantra.marketdata.instruments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Audit 2026-07-02 §3: the in-memory sync audit resets to NEVER_RUN on every restart, so Settings
 * asserted "Data sync: NEVER_RUN" on a long-synced live system. When the instrument master proves a
 * prior sync ({@code max(last_seen_at)}), status() must report OK with that timestamp instead.
 */
class InstrumentSyncStatusFallbackTest {

  private static InstrumentSyncService serviceWith(Instant lastSeen) {
    InstrumentRepository repo =
        new InstrumentRepository(null) {
          @Override
          public Instant maxLastSeenAt() {
            return lastSeen;
          }
        };
    return new InstrumentSyncService(null, repo, null, null, null, null);
  }

  @Test
  void neverRunFallsBackToTheDbProofOfAPriorSync() {
    Instant seen = Instant.parse("2026-07-01T13:00:00Z");
    InstrumentSyncService.SyncStatus s = serviceWith(seen).status();
    assertThat(s.state()).isEqualTo("OK");
    assertThat(s.lastRun()).isEqualTo(seen);
  }

  @Test
  void neverRunStaysWhenTheMasterIsEmpty() {
    assertThat(serviceWith(null).status().state()).isEqualTo("NEVER_RUN");
  }
}
