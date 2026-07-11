package in.arthayantra.strategysignal.paper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.paper.RiskSettingsRepository.Setting;
import in.arthayantra.strategysignal.signals.Books;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Audit M16 — the Books-scoped startup governor assertion auto-seeds a missing (book, key) governor
 * row with a conservative fail-closed default and leaves already-seeded books untouched.
 */
class PaperBookGovernorInitializerTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static Setting present() {
    try {
      return new Setting("kill_switch", JSON.readTree("{\"enabled\": false}"), null);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void seedsAMissingGovernorRowWithAConservativeDefault() {
    RiskSettingsRepository settings = mock(RiskSettingsRepository.class);
    // every (book, key) present EXCEPT the 'other' book's kill_switch
    when(settings.get(any(), any())).thenReturn(Optional.of(present()));
    when(settings.get(eq(Books.OTHER), eq(RiskService.KILL_SWITCH))).thenReturn(Optional.empty());

    new PaperBookGovernorInitializer(settings).seedMissingGovernors();

    verify(settings)
        .insertIfMissing(eq(Books.OTHER), eq(RiskService.KILL_SWITCH), eq("{\"enabled\": false}"));
    // the present rows are never rewritten (and the seed path never uses the clobbering upsert)
    verify(settings, never()).insertIfMissing(eq(Books.SCALPER), any(), any());
    verify(settings, never()).upsert(any(), any(), any());
  }

  @Test
  void aFullySeededBookSetWritesNothing() {
    RiskSettingsRepository settings = mock(RiskSettingsRepository.class);
    when(settings.get(any(), any())).thenReturn(Optional.of(present()));

    new PaperBookGovernorInitializer(settings).seedMissingGovernors();

    verify(settings, never()).insertIfMissing(any(), any(), any());
    verify(settings, never()).upsert(any(), any(), any());
  }

  @Test
  void everyBookAndBothLoadBearingKeysAreChecked() {
    RiskSettingsRepository settings = mock(RiskSettingsRepository.class);
    when(settings.get(any(), any())).thenReturn(Optional.of(present()));

    new PaperBookGovernorInitializer(settings).seedMissingGovernors();

    for (String book : Books.all()) {
      verify(settings).get(eq(book), eq(RiskService.KILL_SWITCH));
      verify(settings).get(eq(book), eq(RiskService.AUTO_PAPER_TRADE));
    }
  }
}
