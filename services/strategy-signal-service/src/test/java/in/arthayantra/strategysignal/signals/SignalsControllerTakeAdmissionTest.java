package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategysignal.signals.SignalRepository.SignalRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The manual take path's half of the ordering fix: the order intent is admitted BEFORE the
 * {@code ACTIVE→TAKEN} CAS, so a take no writer could ever fill leaves the signal {@code ACTIVE}
 * instead of stranding a permanent {@code TAKEN} anchor with no position.
 *
 * <p>Every case here asserts on {@code transitionIf} / {@code publishEvent} rather than on the
 * admission call alone: "the gate was consulted" is not the invariant, "the transition did not
 * happen" is. That is also what makes these ordering proofs — move the admission below the CAS and
 * the refusal cases go red on {@code transitionIf}, not merely on a call count.
 */
class SignalsControllerTakeAdmissionTest {

  private static final ObjectMapper OM = new ObjectMapper().findAndRegisterModules();

  private static SignalRow row(String status) {
    return new SignalRow(
        1L, UUID.randomUUID(), "NFO", "NIFTY24JUN24000CE", "3m", "ENTRY", "BUY",
        new BigDecimal("100"), new BigDecimal("95"), null, new BigDecimal("0.8"),
        OM.nullNode(), status, OffsetDateTime.parse("2026-06-20T10:00:00+05:30"), null,
        new BigDecimal("75"), null, null, null, null, null, null);
  }

  private static TakeAdmission refusing(String code, String reason) {
    return (id, qty) -> TakeAdmission.Verdict.refused(code, reason, Map.of("signalId", id));
  }

  @Test
  void anUnknownLotRefusesTheTakeAndNeverTransitions() {
    SignalRepository repo = mock(SignalRepository.class);
    when(repo.find(1L)).thenReturn(Optional.of(row("ACTIVE")));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SignalsController controller =
        new SignalsController(
            repo,
            events,
            refusing(ErrorCodes.DATA_GAP, "no lot size in the instrument master for NFO:X"));

    assertThatThrownBy(
            () -> controller.taken(1L, new SignalsController.TakenRequest(null, 75, null)))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              ApiException api = (ApiException) e;
              assertThat(api.httpStatus()).isEqualTo(422);
              assertThat(api.code()).isEqualTo(ErrorCodes.DATA_GAP);
              assertThat(api.getMessage()).contains("no lot size in the instrument master");
            });

    verify(repo, never()).transitionIf(anyLong(), any(), any());
    verifyNoInteractions(events);
  }

  @Test
  void aMisalignedQuantityRefusesTheTakeAndNeverTransitions() {
    SignalRepository repo = mock(SignalRepository.class);
    when(repo.find(1L)).thenReturn(Optional.of(row("ACTIVE")));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SignalsController controller =
        new SignalsController(
            repo,
            events,
            refusing(
                ErrorCodes.VALIDATION_FAILED, "qty 74 is not a multiple of the lot size 75"));

    assertThatThrownBy(
            () -> controller.taken(1L, new SignalsController.TakenRequest(null, 74, null)))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              ApiException api = (ApiException) e;
              assertThat(api.httpStatus()).isEqualTo(422);
              assertThat(api.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
              assertThat(api.getMessage()).contains("not a multiple of the lot size");
            });

    verify(repo, never()).transitionIf(anyLong(), any(), any());
    verifyNoInteractions(events);
  }

  @Test
  void anAdmittedTakeStillTransitionsAndPublishesExactlyOnceAfterTheGate() {
    SignalRepository repo = mock(SignalRepository.class);
    when(repo.find(1L)).thenReturn(Optional.of(row("ACTIVE")));
    when(repo.transitionIf(1L, "ACTIVE", "TAKEN")).thenReturn(true);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    TakeAdmission admission = mock(TakeAdmission.class);
    when(admission.admit(anyLong(), anyInt())).thenReturn(TakeAdmission.Verdict.ADMITTED);

    new SignalsController(repo, events, admission)
        .taken(1L, new SignalsController.TakenRequest("101.5", 75, null));

    // Two mocks, so this really does pin the ORDER — the whole point of the change.
    InOrder order = inOrder(admission, repo);
    order.verify(admission).admit(1L, 75);
    order.verify(repo).transitionIf(1L, "ACTIVE", "TAKEN");
    verify(events).publishEvent(new SignalTaken(1L, 75, new BigDecimal("101.5"), false));
  }

  /**
   * The idempotent double-take must stay a 200. The gate is skipped entirely on a non-ACTIVE row,
   * so a signal already TAKEN cannot start 422-ing merely because its instrument master went quiet
   * after the first take — the caller's intended end state (TAKEN) already holds.
   */
  @Test
  void aDoubleTakeOnAnAlreadyTakenSignalStaysAnIdempotentNoOpAndSkipsTheGate() {
    SignalRepository repo = mock(SignalRepository.class);
    when(repo.find(1L)).thenReturn(Optional.of(row("TAKEN")));
    when(repo.transitionIf(1L, "ACTIVE", "TAKEN")).thenReturn(false);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    TakeAdmission admission =
        refusing(ErrorCodes.DATA_GAP, "would refuse if it were ever consulted here");

    SignalViews.SignalDto dto =
        new SignalsController(repo, events, admission)
            .taken(1L, new SignalsController.TakenRequest(null, 75, null));

    assertThat(dto.status()).isEqualTo("TAKEN");
    verifyNoInteractions(events);
  }

  /**
   * ⚠️ Cross-vendor review round 1, Major — the interleaving the ACTIVE gate alone could not survive.
   * Caller A reads {@code ACTIVE}; caller B (a concurrent manual take, or the auto-paper listener)
   * wins {@code ACTIVE→TAKEN}; A's admission then refuses. A must still get the idempotent 200,
   * because the end state it asked for HAS been reached — just not by A. Round 1 returned 422 here,
   * and no test could see it: the double-take case reads {@code TAKEN} on the FIRST read, so it never
   * exercises this ordering at all.
   */
  @Test
  void aRefusalLosingTheRaceToAConcurrentTakeStillAnswersTheIdempotent200() {
    SignalRepository repo = mock(SignalRepository.class);
    // First read (requireExists) sees ACTIVE; every later read sees the row B already flipped.
    when(repo.find(1L)).thenReturn(Optional.of(row("ACTIVE")), Optional.of(row("TAKEN")));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    SignalViews.SignalDto dto =
        new SignalsController(
                repo, events, refusing(ErrorCodes.DATA_GAP, "no lot size in the master"))
            .taken(1L, new SignalsController.TakenRequest(null, 75, null));

    assertThat(dto.status()).isEqualTo("TAKEN");
    verifyNoInteractions(events); // B opened it; A must not publish a second SignalTaken
  }

  /** Same contract when the admission THROWS rather than refusing — a 500 would be just as wrong. */
  @Test
  void anAdmissionFailureLosingTheRaceAlsoAnswersTheIdempotent200() {
    SignalRepository repo = mock(SignalRepository.class);
    when(repo.find(1L)).thenReturn(Optional.of(row("ACTIVE")), Optional.of(row("TAKEN")));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    TakeAdmission exploding =
        (id, qty) -> {
          throw new IllegalStateException("instrument master unreachable");
        };

    SignalViews.SignalDto dto =
        new SignalsController(repo, events, exploding)
            .taken(1L, new SignalsController.TakenRequest(null, 75, null));

    assertThat(dto.status()).isEqualTo("TAKEN");
    verify(repo, never()).transitionIf(anyLong(), any(), any());
    verifyNoInteractions(events);
  }

  /**
   * The other side of the same coin: when the row is still genuinely ACTIVE on the re-read, the
   * refusal stands. Without this, "re-read and return 200" could degenerate into never refusing.
   */
  @Test
  void aRefusalThatDidNotLoseTheRaceStillRefuses() {
    SignalRepository repo = mock(SignalRepository.class);
    when(repo.find(1L)).thenReturn(Optional.of(row("ACTIVE"))); // still ACTIVE on every read
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    assertThatThrownBy(
            () ->
                new SignalsController(
                        repo, events, refusing(ErrorCodes.DATA_GAP, "no lot size in the master"))
                    .taken(1L, new SignalsController.TakenRequest(null, 75, null)))
        .isInstanceOf(ApiException.class);

    verify(repo, never()).transitionIf(anyLong(), any(), any());
    verifyNoInteractions(events);
  }

  /** The CAS still owns the race: admitted, but a concurrent auto-take already won ⇒ no publish. */
  @Test
  void anAdmittedTakeThatLosesTheCasPublishesNothing() {
    SignalRepository repo = mock(SignalRepository.class);
    when(repo.find(1L)).thenReturn(Optional.of(row("ACTIVE")));
    when(repo.transitionIf(1L, "ACTIVE", "TAKEN")).thenReturn(false);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    new SignalsController(repo, events, (id, qty) -> TakeAdmission.Verdict.ADMITTED)
        .taken(1L, new SignalsController.TakenRequest(null, 75, null));

    verify(repo).transitionIf(1L, "ACTIVE", "TAKEN");
    verifyNoInteractions(events);
  }
}
