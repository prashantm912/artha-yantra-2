package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The H22 terminal resolve, run against the REAL Timescale + flyway lineage.
 *
 * <p>The guard lives in a SQL WHERE clause, so the test that proves it must run the SQL — the same
 * rule {@code SwingCatchUpStateRepositoryIntegrationTest} was written for. A mocked repository
 * cannot see the trap this fix exists to avoid: {@link SwingPaperEffectRepository#skipEntry}, which
 * already carries the exact {@code SKIPPED}/{@code CONFIRMED} vocabulary we want, requires
 * {@code decision='UNDECIDED'} and therefore matches ZERO rows for the {@code REQUIRED} lease
 * {@code PaperSignalListener} is holding when a governor refuses the fill. Reusing it would have
 * looked right, compiled, and silently changed nothing.
 *
 * <p>Unique batch per method — the singleton DB has no per-method cleanup and state survives
 * surefire reruns.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SwingPaperEffectRefusalIntegrationTest extends StrategySignalIntegrationTestBase {

  /** A past, closed session: nothing in this class depends on the calendar, only on the row shape. */
  private static final LocalDate SESSION = LocalDate.of(2026, 8, 13);

  @Autowired private SwingPaperEffectRepository effects;

  @Test
  void refuseEntryClosesAClaimedRequiredLease_whichSkipEntryCannotDo() {
    String batch = "h22-refuse-" + System.nanoTime();
    String symbol = "SALSTEEL";
    long signalId = System.nanoTime();

    long effectId = claimedRequiredEntry(batch, symbol, signalId, 206);

    // The negative control, and the whole reason refuseEntry exists: the vocabulary auto-paper
    // already uses for "no money effect" cannot close THIS row.
    assertThat(effects.skipEntry(signalId))
        .as("skipEntry only matches decision='UNDECIDED'")
        .isFalse();
    assertThat(effects.find(effectId).orElseThrow().status()).isEqualTo("CLAIMED");

    // Before: the session cannot reach DONE and the sweep keeps republishing the retry.
    assertThat(effects.allConfirmed(batch, SESSION)).isFalse();
    assertThat(effects.pending(batch, SESSION))
        .extracting(SwingPaperEffectRepository.Effect::id)
        .containsExactly(effectId);

    assertThat(effects.refuseEntry(effectId)).isTrue();

    SwingPaperEffectRepository.Effect after = effects.find(effectId).orElseThrow();
    assertThat(after.status()).isEqualTo("CONFIRMED");
    assertThat(after.decision())
        .as("SKIPPED, never REQUIRED — no money effect happened and the row must not claim one")
        .isEqualTo("SKIPPED");

    // After: nothing left to repair, and the session can complete.
    assertThat(effects.pending(batch, SESSION)).isEmpty();
    assertThat(effects.allConfirmed(batch, SESSION)).isTrue();
    assertThat(effects.refuseEntry(effectId)).as("terminal, so a second call is a no-op").isFalse();
  }

  @Test
  void refuseEntryLeavesAnUnclaimedExpectedRowAlone() {
    String batch = "h22-unclaimed-" + System.nanoTime();
    String symbol = "SALSTEEL";
    long signalId = System.nanoTime();

    assertThat(effects.expectEntry(batch, SESSION, symbol)).isTrue();
    effects.bindEntry(batch, SESSION, symbol, signalId, 206);
    assertThat(effects.requireEntry(signalId)).isTrue();
    long effectId = effects.findEntryBySignal(signalId).orElseThrow().id();

    // Only the holder of a live lease may close it: an EXPECTED row has no in-flight open to
    // refuse, so a stray call must not forfeit an entry that was never attempted.
    assertThat(effects.refuseEntry(effectId)).isFalse();
    SwingPaperEffectRepository.Effect untouched = effects.find(effectId).orElseThrow();
    assertThat(untouched.status()).isEqualTo("EXPECTED");
    assertThat(untouched.decision()).isEqualTo("REQUIRED");
  }

  /** Drives the real ledger through the exact states the listener sees before its paper open. */
  private long claimedRequiredEntry(String batch, String symbol, long signalId, long qty) {
    assertThat(effects.expectEntry(batch, SESSION, symbol)).isTrue();
    effects.bindEntry(batch, SESSION, symbol, signalId, qty);
    assertThat(effects.requireEntry(signalId)).isTrue();
    long effectId = effects.findEntryBySignal(signalId).orElseThrow().id();
    Optional<SwingPaperEffectRepository.Effect> claimed = effects.claimEntryEffect(effectId, 0);
    assertThat(claimed).isPresent();
    assertThat(claimed.orElseThrow().status()).isEqualTo("CLAIMED");
    assertThat(claimed.orElseThrow().decision()).isEqualTo("REQUIRED");
    return effectId;
  }
}
