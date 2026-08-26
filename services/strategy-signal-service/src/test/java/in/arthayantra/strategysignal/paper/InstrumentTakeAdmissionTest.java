package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.TakeAdmission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The gate's own rules. Each case pins that the verdict is decided on the SAME leg and the SAME
 * quantity {@code PaperSignalListener} will actually route — a gate that disagrees with the writer
 * would refuse takes that fill perfectly well, which on a live money path is worse than the defect
 * it replaces.
 */
class InstrumentTakeAdmissionTest {

  private static final ObjectMapper OM = new ObjectMapper();

  private static SignalRepository.SignalRow row(
      String exchange, String tradingsymbol, String tradeableExchange, String tradeableSymbol,
      JsonNode scalperDetail) {
    return new SignalRepository.SignalRow(
        7L, UUID.randomUUID(), exchange, tradingsymbol, "3m", "ENTRY", "BUY",
        new BigDecimal("100"), null, null, new BigDecimal("0.7"), null, "ACTIVE",
        null, null, new BigDecimal("75"), tradeableExchange, tradeableSymbol, scalperDetail,
        null, null, null);
  }

  private static InstrumentMeta meta(long lot) {
    return new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), lot);
  }

  /** The DEPLOYED configuration: {@code artha.scalper.execution=paper}, so only paper's intents. */
  private static InstrumentTakeAdmission admission(
      SignalRepository signals, InstrumentMetaClient instruments) {
    return new InstrumentTakeAdmission(signals, instruments, new SimpleMeterRegistry(), "paper");
  }

  /** Live execution ARMED — {@code LiveOrderService}'s raw-quantity intent joins the check. */
  private static InstrumentTakeAdmission liveAdmission(
      SignalRepository signals, InstrumentMetaClient instruments) {
    return new InstrumentTakeAdmission(signals, instruments, new SimpleMeterRegistry(), "live");
  }

  private static SignalRepository store(SignalRepository.SignalRow row) {
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(7L)).thenReturn(Optional.of(row));
    return signals;
  }

  @Test
  void anUnknownLotOnTheRoutedLegIsRefusedAsADataGap() {
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    when(instruments.meta("NFO", "NIFTY24JUN24000CE")).thenReturn(meta(0));

    TakeAdmission.Verdict verdict =
        admission(store(row("NFO", "NIFTY24JUN24000CE", null, null, null)), instruments)
            .admit(7L, 75);

    assertThat(verdict.admitted()).isFalse();
    assertThat(verdict.code()).isEqualTo(ErrorCodes.DATA_GAP);
    assertThat(verdict.reason()).contains("no lot size in the instrument master");
    assertThat(verdict.details()).containsEntry("tradingsymbol", "NIFTY24JUN24000CE");
  }

  @Test
  void aQuantityThatIsNotAWholeLotIsRefusedAsAValidationFailure() {
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    when(instruments.meta("NFO", "NIFTY24JUN24000CE")).thenReturn(meta(75));

    TakeAdmission.Verdict verdict =
        admission(store(row("NFO", "NIFTY24JUN24000CE", null, null, null)), instruments)
            .admit(7L, 74);

    assertThat(verdict.admitted()).isFalse();
    assertThat(verdict.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
    assertThat(verdict.reason()).contains("not a multiple of the lot size 75");
  }

  @Test
  void aWholeLotQuantityIsAdmitted() {
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    when(instruments.meta("NFO", "NIFTY24JUN24000CE")).thenReturn(meta(75));

    assertThat(
            admission(store(row("NFO", "NIFTY24JUN24000CE", null, null, null)), instruments)
                .admit(7L, 150)
                .admitted())
        .isTrue();
  }

  /**
   * A directional scalper take routes the TRADEABLE option, not the index future the signal is keyed
   * on — so the lot must be read there, or the gate would admit against the wrong instrument.
   */
  @Test
  void aScalperTakeIsAdmittedAgainstTheTradeableOptionLegNotTheSignalsOwnLeg() throws Exception {
    JsonNode detail = OM.readTree("{\"side\":\"CE\",\"option_ltp\":\"120\"}");
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    when(instruments.meta("NFO", "NIFTY24JUN24000CE")).thenReturn(meta(75));

    TakeAdmission.Verdict verdict =
        admission(
                store(row("NSE", "NIFTY 50", "NFO", "NIFTY24JUN24000CE", detail)), instruments)
            .admit(7L, 75);

    assertThat(verdict.admitted()).isTrue();
    verify(instruments).meta("NFO", "NIFTY24JUN24000CE");
    verify(instruments, org.mockito.Mockito.never()).meta("NSE", "NIFTY 50");
  }

  private static JsonNode straddle() throws Exception {
    return OM.readTree(
        "{\"side\":\"NEUTRAL\",\"legs\":["
            + "{\"exchange\":\"NFO\",\"tradingsymbol\":\"N24JUN24000CE\",\"option_type\":\"CE\","
            + "\"option_ltp\":\"130\"},"
            + "{\"exchange\":\"NFO\",\"tradingsymbol\":\"N24JUN24000PE\",\"option_type\":\"PE\","
            + "\"option_ltp\":\"125\"}]}");
  }

  /**
   * The PAPER writer's straddle quantity is {@code StraddleLegs#combinedQty}, which FLOORS to a whole
   * lot, so its pair is aligned by construction and both legs are checked at THAT quantity.
   *
   * <p>⚠️ <b>This javadoc used to end "…never at the raw suggested qty (which is what the writer
   * would never place)", and that clause was FALSE</b> — cross-vendor review round 1, Critical.
   * There are TWO writers: {@code LiveOrderService:98} checks the RAW {@code event.qty()}. The claim
   * is true of {@code PaperSignalListener} and only of it, which is exactly the belief that let the
   * first cut of this gate admit a take the live writer refuses. The raw-quantity intent now has its
   * own cases below, and {@code TakeAdmissionWriterAgreementTest} pins the whole question against the
   * real writers rather than against a claim in a comment.
   */
  @Test
  void aStraddleIsAdmittedOnBothLegsAtThePaperWritersCombinedLotAlignedQuantity() throws Exception {
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    when(instruments.meta("NFO", "N24JUN24000CE")).thenReturn(meta(65));
    when(instruments.meta("NFO", "N24JUN24000PE")).thenReturn(meta(65));

    TakeAdmission.Verdict verdict =
        admission(store(row("NSE", "NIFTY 50", "NFO", "N24JUN24000CE", straddle())), instruments)
            .admit(7L, 65);

    assertThat(verdict.admitted()).isTrue();
    verify(instruments, org.mockito.Mockito.atLeastOnce()).meta("NFO", "N24JUN24000PE");
  }

  /** A PE leg whose own lot is unknown fails {@code openPair} — so the pair is refused up front. */
  @Test
  void aStraddleWhosePutLegHasNoLotIsRefused() throws Exception {
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    when(instruments.meta("NFO", "N24JUN24000CE")).thenReturn(meta(65));
    when(instruments.meta("NFO", "N24JUN24000PE")).thenReturn(meta(0));

    TakeAdmission.Verdict verdict =
        admission(store(row("NSE", "NIFTY 50", "NFO", "N24JUN24000CE", straddle())), instruments)
            .admit(7L, 65);

    assertThat(verdict.admitted()).isFalse();
    assertThat(verdict.code()).isEqualTo(ErrorCodes.DATA_GAP);
    assertThat(verdict.details()).containsEntry("tradingsymbol", "N24JUN24000PE");
  }

  /**
   * With an unknown CE lot {@code openStraddle} DEGRADES to the single primary leg rather than
   * opening a pair, so the gate must degrade with it — refusing here would block a take the writer
   * was going to place happily.
   */
  @Test
  void aStraddleWithAnUnknownCallLotDegradesToTheSingleLegVerdict() throws Exception {
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    when(instruments.meta("NFO", "N24JUN24000CE")).thenReturn(meta(0));
    // The primary tradeable leg the take degrades to — a DIFFERENT symbol, so the two lookups
    // cannot be confused for one another.
    when(instruments.meta("NFO", "NIFTY24JUN24000CE")).thenReturn(meta(75));

    TakeAdmission.Verdict verdict =
        admission(
                store(row("NSE", "NIFTY 50", "NFO", "NIFTY24JUN24000CE", straddle())), instruments)
            .admit(7L, 75);

    // The straddle arm bailed on the unknown CE lot and the SINGLE-leg arm decided the verdict, on
    // the tradeable leg at the raw qty — exactly what openStraddle's degrade path would open.
    assertThat(verdict.admitted()).isTrue();
    verify(instruments).meta("NFO", "NIFTY24JUN24000CE");
    verify(instruments, org.mockito.Mockito.never()).meta("NFO", "N24JUN24000PE");
  }

  /**
   * The round-1 Critical, at unit level: {@code combinedQty} floors 50 up to a whole lot of 65, so
   * the paper writer is satisfied — but {@code LiveOrderService:98} checks the raw 50 against the
   * same lot and refuses. Admitting on the paper intent alone commits the CAS and strands the anchor,
   * which is the defect this whole change exists to close.
   */
  @Test
  void aStraddleWhoseRawQtyTheLiveWriterRefusesIsRefusedWhenLiveExecutionIsArmed() throws Exception {
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    when(instruments.meta("NFO", "N24JUN24000CE")).thenReturn(meta(65));
    when(instruments.meta("NFO", "N24JUN24000PE")).thenReturn(meta(65));

    TakeAdmission.Verdict verdict =
        liveAdmission(
                store(row("NSE", "NIFTY 50", "NFO", "N24JUN24000CE", straddle())), instruments)
            .admit(7L, 50);

    assertThat(verdict.admitted()).isFalse();
    assertThat(verdict.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
    assertThat(verdict.reason()).contains("qty 50 is not a multiple of the lot size 65");
  }

  /**
   * The mirror, and it is the reason the live intent is conditioned rather than always applied:
   * {@code LiveOrderService:75-77} returns before any check while {@code artha.scalper.execution} is
   * {@code paper} (the deployed value), so the SAME take fills fine and must be ADMITTED. Refusing it
   * would be an over-refusal on a live money path — worse than the defect being fixed.
   */
  @Test
  void theSameStraddleIsAdmittedWhileLiveExecutionIsDisarmed() throws Exception {
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    when(instruments.meta("NFO", "N24JUN24000CE")).thenReturn(meta(65));
    when(instruments.meta("NFO", "N24JUN24000PE")).thenReturn(meta(65));

    assertThat(
            admission(store(row("NSE", "NIFTY 50", "NFO", "N24JUN24000CE", straddle())), instruments)
                .admit(7L, 50)
                .admitted())
        .isTrue();
  }

  /**
   * The other divergence axis: with a tradeable symbol but NO scalper side-channel the paper writer
   * routes the PRIMARY leg ({@code openSingle:295-297} needs both) while the live writer routes the
   * TRADEABLE one ({@code LiveOrderService:87} needs only the symbol). The gate must check both.
   */
  @Test
  void aTradeableSymbolWithoutScalperDetailIsCheckedOnBothWritersLegs() {
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    when(instruments.meta("NSE", "SALSTEEL")).thenReturn(meta(1));
    when(instruments.meta("NFO", "N24JUN24000CE")).thenReturn(meta(75));

    TakeAdmission.Verdict verdict =
        liveAdmission(store(row("NSE", "SALSTEEL", "NFO", "N24JUN24000CE", null)), instruments)
            .admit(7L, 50);

    // Paper's equity leg admits 50 (lot 1); the live writer's option leg does not (50 % 75).
    assertThat(verdict.admitted()).isFalse();
    assertThat(verdict.details()).containsEntry("tradingsymbol", "N24JUN24000CE");
  }

  @Test
  void aQtyLessTakeCarriesNoOrderIntentAndIsAdmittedWithoutTouchingTheMaster() {
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    SignalRepository signals = mock(SignalRepository.class);

    assertThat(admission(signals, instruments).admit(7L, null).admitted()).isTrue();
    assertThat(admission(signals, instruments).admit(7L, 0).admitted()).isTrue();

    verifyNoInteractions(instruments, signals);
  }

  @Test
  void anUnknownSignalIsLeftToTheCallersOwnNotFoundHandling() {
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(7L)).thenReturn(Optional.empty());

    assertThat(admission(signals, instruments).admit(7L, 75).admitted()).isTrue();
    verify(instruments, org.mockito.Mockito.never()).meta(anyString(), any());
  }
}
