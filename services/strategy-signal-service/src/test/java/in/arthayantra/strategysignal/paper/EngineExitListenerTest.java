package in.arthayantra.strategysignal.paper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRepository;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRetry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EngineExitListenerTest {

  @Test
  void retryClosesOnlyThePositionIdBoundByTheOldEffect() {
    PaperService paper = mock(PaperService.class);
    SignalRepository signals = mock(SignalRepository.class);
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    SwingPaperEffectRepository effects = mock(SwingPaperEffectRepository.class);
    SwingPaperEffectRepository.Effect effect = mock(SwingPaperEffectRepository.Effect.class);
    when(effect.id()).thenReturn(99L);
    when(effect.decision()).thenReturn("REQUIRED");
    when(effect.targetPositionIds()).thenReturn(List.of(42L));
    when(effects.findCloseByAnchor(7L)).thenReturn(Optional.of(effect));
    when(effects.claimClose(99L)).thenReturn(Optional.of(effect));
    when(paper.closeForPosition(42L, "STOP_LOSS", new BigDecimal("101"))).thenReturn(1);

    new EngineExitListener(paper, signals, positions, effects)
        .onSwingPaperEffectRetry(
            SwingPaperEffectRetry.exit(
                "manas-arora", java.time.LocalDate.of(2026, 7, 17), 7L, 8L, "STOP_LOSS",
                new BigDecimal("101")));

    verify(paper).closeForPosition(42L, "STOP_LOSS", new BigDecimal("101"));
    verify(paper, never()).closeForSignal(any(Long.class), any(), any());
    verify(effects).confirm(99L);
  }
}
