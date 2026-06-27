package in.arthayantra.strategysignal.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.registry.StrategyRepository.StrategyRow;
import in.arthayantra.strategysignal.registry.StrategyRepository.VersionRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Row-tag re-sync (the §12.3 S24 gate arming the engine reads from {@code strategy.tags()}): METADATA
 * only — it never mints a version or perturbs a D18 checksum, it is idempotent, and it hot-swaps the
 * live engine ONLY when the strategy is already published (a draft the engine skips needs no event).
 */
class RegistryServiceTagsTest {

  private final StrategyRepository repo = mock(StrategyRepository.class);
  private final MarketDataInstrumentClient instruments = mock(MarketDataInstrumentClient.class);
  private final StrategyChangedPublisher changed = mock(StrategyChangedPublisher.class);
  private final RegistryService service = new RegistryService(repo, instruments, changed);

  private static StrategyRow row(UUID id, List<String> tags, UUID publishedVersionId) {
    return new StrategyRow(
        id, "scalp-x", "Scalp X", null, "owner", tags, true, publishedVersionId,
        OffsetDateTime.now(), OffsetDateTime.now(), false, null);
  }

  @Test
  void updateTagsIsANoOpWhenTheTagsAlreadyMatch() {
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.of(row(id, List.of("scalper", "rsi-s24-bands"), null)));
    assertThat(service.updateTags(id, List.of("scalper", "rsi-s24-bands"))).isFalse();
    verify(repo, never()).updateTags(any(), any());
    verify(changed, never()).publish(any(), anyString(), anyString(), any());
  }

  @Test
  void updateTagsRewritesTheRowAndSkipsTheEventForADraft() {
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.of(row(id, List.of("scalper"), null)));
    List<String> armed = List.of("scalper", "rsi-s24-bands", "overbought-defer");
    assertThat(service.updateTags(id, armed)).isTrue();
    verify(repo).updateTags(id, armed);
    // a draft is not loaded by the engine, so no hot-swap event.
    verify(changed, never()).publish(any(), anyString(), anyString(), any());
  }

  @Test
  void updateTagsHotSwapsThePublishedEngine() {
    UUID id = UUID.randomUUID();
    UUID pub = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.of(row(id, List.of("scalper"), pub)));
    when(repo.findVersionById(pub))
        .thenReturn(
            Optional.of(
                new VersionRow(
                    pub, id, "1.0.0", "", null, "v1", "abc", "published", null, "owner", null, null)));
    assertThat(service.updateTags(id, List.of("scalper", "rsi-s24-bands"))).isTrue();
    verify(repo).updateTags(eq(id), any());
    verify(changed).publish(eq(id), eq("scalp-x"), eq("TAGS"), eq("1.0.0"));
  }
}
