package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.execution.LiveOrderService;
import in.arthayantra.strategysignal.execution.OrderGateway;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SignalTaken;
import in.arthayantra.strategysignal.signals.TakeAdmission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * <b>The gate must agree with the WRITERS, not with my beliefs about them.</b>
 *
 * <p>Cross-vendor review round 1 found a Critical that every other suite in this change was blind to,
 * because those suites encode what I believed each writer does. One of them —
 * {@code InstrumentTakeAdmissionTest}'s straddle case — asserted outright that no writer uses the raw
 * quantity, and that assertion was simply FALSE: {@code LiveOrderService:98} checks the raw
 * {@code event.qty()}. Belief-shaped tests cannot catch a wrong belief.
 *
 * <p>So this suite does not restate the rules. For each shape it DRIVES THE REAL WRITERS over the
 * same {@code SignalTaken} the gate judged, observes what they actually did, and asserts the gate's
 * verdict matches:
 *
 * <ul>
 *   <li><b>Live writer:</b> a real {@link LiveOrderService} against a mock {@link OrderGateway}. It
 *       performs its OWN refusal internally, so "did {@code place} get called" is the writer's own
 *       verdict, directly observed — no oracle of mine is involved at all.
 *   <li><b>Paper writer:</b> a real {@link PaperSignalListener} against a mock {@link PaperService},
 *       capturing the {@link PaperService.OrderRequest}s it emits. The LEG and the QUANTITY — the two
 *       axes on which the writers diverged — therefore come from the real writer. Only the final lot
 *       predicate is applied here, transcribed from {@code PaperService#openOrder:881-909}, because a
 *       mocked {@code PaperService} cannot throw its own refusal. Null leg fields resolve to the
 *       signal's primary leg exactly as {@code openOrder:800-802} resolves them.
 * </ul>
 *
 * <p>A new divergence between the two writers, or between a writer and the gate, fails HERE — which
 * is the whole point of the suite.
 */
class TakeAdmissionWriterAgreementTest {

  private static final ObjectMapper OM = new ObjectMapper();

  /** One shape under test: the signal, the take quantity, and whether live execution is armed. */
  private record Shape(
      String name,
      SignalRepository.SignalRow row,
      int qty,
      boolean executionLive,
      Map<String, Long> lots) {}

  /** What the real writers did with one take. */
  private record WriterOutcome(boolean paperRefuses, boolean liveRefuses) {
    boolean anyRefuses() {
      return paperRefuses || liveRefuses;
    }
  }

  private static SignalRepository.SignalRow row(
      String exchange, String tradingsymbol, String tradeableExchange, String tradeableSymbol,
      JsonNode scalperDetail) {
    return new SignalRepository.SignalRow(
        7L, UUID.randomUUID(), exchange, tradingsymbol, "3m", "ENTRY", "BUY",
        new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"), new BigDecimal("0.7"),
        null, "ACTIVE", null, null, new BigDecimal(1), tradeableExchange, tradeableSymbol,
        scalperDetail, null, null, null);
  }

  private static JsonNode straddleDetail() {
    try {
      return OM.readTree(
          "{\"side\":\"NEUTRAL\",\"legs\":["
              + "{\"exchange\":\"NFO\",\"tradingsymbol\":\"NCE\",\"option_type\":\"CE\","
              + "\"option_ltp\":\"130\"},"
              + "{\"exchange\":\"NFO\",\"tradingsymbol\":\"NPE\",\"option_type\":\"PE\","
              + "\"option_ltp\":\"125\"}]}");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode directionalDetail() {
    try {
      return OM.readTree("{\"side\":\"CE\",\"option_ltp\":\"120\"}");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Every shape worth pinning, including both axes on which the two writers diverge. */
  private static List<Shape> shapes() {
    List<Shape> shapes = new ArrayList<>();
    shapes.add(
        new Shape(
            "directional primary leg, aligned",
            row("NFO", "NFUT", null, null, null), 75, true, Map.of("NFO/NFUT", 75L)));
    shapes.add(
        new Shape(
            "directional primary leg, MISALIGNED",
            row("NFO", "NFUT", null, null, null), 74, true, Map.of("NFO/NFUT", 75L)));
    shapes.add(
        new Shape(
            "directional primary leg, UNKNOWN lot",
            row("NFO", "NFUT", null, null, null), 75, true, Map.of("NFO/NFUT", 0L)));
    shapes.add(
        new Shape(
            "scalper tradeable option leg, aligned",
            row("NSE", "NIFTY 50", "NFO", "NCE", directionalDetail()),
            75, true, Map.of("NFO/NCE", 75L, "NSE/NIFTY 50", 1L)));
    // ⚠️ LEG divergence: openSingle:295-297 needs tradeableTradingsymbol AND scalperDetail, so paper
    // routes the PRIMARY equity leg (lot 1, admits 50); LiveOrderService:87 needs only the symbol, so
    // it routes the OPTION leg and refuses 50 against lot 75.
    shapes.add(
        new Shape(
            "tradeable symbol with NO scalper detail — writers route DIFFERENT legs",
            row("NSE", "SALSTEEL", "NFO", "NCE", null),
            50, true, Map.of("NFO/NCE", 75L, "NSE/SALSTEEL", 1L)));
    shapes.add(
        new Shape(
            "straddle where combinedQty EQUALS the raw qty",
            row("NSE", "NIFTY 50", "NFO", "NCE", straddleDetail()),
            65, true, Map.of("NFO/NCE", 65L, "NFO/NPE", 65L, "NSE/NIFTY 50", 1L)));
    // ⚠️ THE ROUND-1 CRITICAL: combinedQty floors 50 up to 65 (paper is happy) while the live writer
    // checks the raw 50 against lot 65 and refuses. The first cut of the gate ADMITTED this.
    shapes.add(
        new Shape(
            "straddle where combinedQty DIFFERS from the raw qty, execution LIVE",
            row("NSE", "NIFTY 50", "NFO", "NCE", straddleDetail()),
            50, true, Map.of("NFO/NCE", 65L, "NFO/NPE", 65L, "NSE/NIFTY 50", 1L)));
    // The same take with execution=paper: the live writer returns at :75-77 without checking
    // anything, so this MUST be admitted. Refusing it would be an over-refusal regression.
    shapes.add(
        new Shape(
            "straddle where combinedQty DIFFERS from the raw qty, execution PAPER",
            row("NSE", "NIFTY 50", "NFO", "NCE", straddleDetail()),
            50, false, Map.of("NFO/NCE", 65L, "NFO/NPE", 65L, "NSE/NIFTY 50", 1L)));
    shapes.add(
        new Shape(
            "straddle whose PUT leg has no lot",
            row("NSE", "NIFTY 50", "NFO", "NCE", straddleDetail()),
            65, false, Map.of("NFO/NCE", 65L, "NFO/NPE", 0L, "NSE/NIFTY 50", 1L)));
    shapes.add(
        new Shape(
            "straddle whose CALL leg has no lot — degrades to the single leg",
            row("NSE", "NIFTY 50", "NFO", "NCE", straddleDetail()),
            75, false, Map.of("NFO/NCE", 0L, "NFO/NPE", 65L, "NSE/NIFTY 50", 1L)));
    return shapes;
  }

  @Test
  void theGateAdmitsExactlyWhatBothWritersWouldAccept() {
    List<String> mismatches = new ArrayList<>();
    for (Shape shape : shapes()) {
      boolean gateAdmits = gateVerdict(shape).admitted();
      WriterOutcome writers = driveWriters(shape);
      if (gateAdmits == writers.anyRefuses()) {
        mismatches.add(
            String.format(
                "%s -> gate %s but paperRefuses=%s liveRefuses=%s",
                shape.name(),
                gateAdmits ? "ADMITS" : "REFUSES",
                writers.paperRefuses(),
                writers.liveRefuses()));
      }
    }
    assertThat(mismatches)
        .as("the gate must refuse exactly when a real writer would refuse the same SignalTaken")
        .isEmpty();
  }

  /**
   * The Critical, pinned as its own named case so a regression reads as itself rather than as one
   * line inside the table above.
   */
  @Test
  void aStraddleWhoseRawQtyTheLiveWriterWouldRefuseIsNotAdmitted() {
    Shape shape =
        new Shape(
            "critical",
            row("NSE", "NIFTY 50", "NFO", "NCE", straddleDetail()),
            50, true, Map.of("NFO/NCE", 65L, "NFO/NPE", 65L, "NSE/NIFTY 50", 1L));

    TakeAdmission.Verdict verdict = gateVerdict(shape);

    assertThat(verdict.admitted())
        .as("combinedQty floors 50 to 65 for paper, but LiveOrderService:98 refuses the raw 50")
        .isFalse();
    // And the live writer really does refuse it — asserted against the real service, not assumed.
    OrderGateway gateway = mock(OrderGateway.class);
    liveWriter(shape, gateway).onSignalTaken(taken(shape));
    verify(gateway, never()).place(any());
  }

  /** The mirror: with execution=paper the same take must still be ADMITTED (no over-refusal). */
  @Test
  void theSameStraddleIsAdmittedWhileLiveExecutionIsDisarmed() {
    Shape shape =
        new Shape(
            "mirror",
            row("NSE", "NIFTY 50", "NFO", "NCE", straddleDetail()),
            50, false, Map.of("NFO/NCE", 65L, "NFO/NPE", 65L, "NSE/NIFTY 50", 1L));

    assertThat(gateVerdict(shape).admitted())
        .as("LiveOrderService:75-77 returns before any check while execution=paper")
        .isTrue();
  }

  // ---------------------------------------------------------------- harness

  private static SignalTaken taken(Shape shape) {
    return new SignalTaken(7L, shape.qty(), new BigDecimal("100"), shape.row().scalperDetail() != null);
  }

  private static InstrumentMetaClient instruments(Shape shape) {
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    when(instruments.meta(any(), any()))
        .thenAnswer(
            invocation -> {
              String key = invocation.getArgument(0) + "/" + invocation.getArgument(1);
              long lot = shape.lots().getOrDefault(key, 0L);
              return new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), lot);
            });
    return instruments;
  }

  private static SignalRepository signals(Shape shape) {
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(7L)).thenReturn(Optional.of(shape.row()));
    return signals;
  }

  private static TakeAdmission.Verdict gateVerdict(Shape shape) {
    return new InstrumentTakeAdmission(
            signals(shape),
            instruments(shape),
            new SimpleMeterRegistry(),
            shape.executionLive() ? "live" : "paper")
        .admit(7L, shape.qty());
  }

  private static LiveOrderService liveWriter(Shape shape, OrderGateway gateway) {
    return new LiveOrderService(
        shape.executionLive() ? "live" : "paper", gateway, signals(shape), instruments(shape));
  }

  /** Runs BOTH real writers over the take and reports what each of them actually did. */
  private static WriterOutcome driveWriters(Shape shape) {
    OrderGateway gateway = mock(OrderGateway.class);
    AtomicInteger placed = new AtomicInteger();
    when(gateway.place(any()))
        .thenAnswer(
            invocation -> {
              placed.incrementAndGet();
              return new OrderGateway.OrderAck("id", "COMPLETE", null);
            });
    liveWriter(shape, gateway).onSignalTaken(taken(shape));
    // The live writer performs its own refusal, so this IS its verdict — nothing is inferred.
    boolean liveRefuses = shape.executionLive() && placed.get() == 0;

    PaperService paper = mock(PaperService.class);
    new PaperSignalListener(
            paper, mock(ScalperAccountModel.class), signals(shape), null, instruments(shape))
        .onSignalTaken(taken(shape));
    boolean paperRefuses = paperIntentsViolateTheLotRule(shape, paper);
    return new WriterOutcome(paperRefuses, liveRefuses);
  }

  /**
   * The lot predicate {@code PaperService#openOrder:881-909} would have applied, run over the REAL
   * {@link PaperService.OrderRequest}s the real listener emitted (a mocked service cannot throw its
   * own refusal). Null leg fields fall back to the signal's primary leg, as {@code openOrder:800-802}
   * does.
   */
  private static boolean paperIntentsViolateTheLotRule(Shape shape, PaperService paper) {
    List<PaperService.OrderRequest> requests = new ArrayList<>();
    ArgumentCaptor<PaperService.OrderRequest> single =
        ArgumentCaptor.forClass(PaperService.OrderRequest.class);
    ArgumentCaptor<PaperService.OrderRequest> pairFirst =
        ArgumentCaptor.forClass(PaperService.OrderRequest.class);
    ArgumentCaptor<PaperService.OrderRequest> pairSecond =
        ArgumentCaptor.forClass(PaperService.OrderRequest.class);
    org.mockito.Mockito.verify(paper, org.mockito.Mockito.atLeast(0))
        .openOrder(single.capture());
    org.mockito.Mockito.verify(paper, org.mockito.Mockito.atLeast(0))
        .openScalperOrder(single.capture());
    org.mockito.Mockito.verify(paper, org.mockito.Mockito.atLeast(0))
        .openPair(pairFirst.capture(), pairSecond.capture());
    org.mockito.Mockito.verify(paper, org.mockito.Mockito.atLeast(0))
        .openScalperPair(pairFirst.capture(), pairSecond.capture());
    requests.addAll(single.getAllValues());
    requests.addAll(pairFirst.getAllValues());
    requests.addAll(pairSecond.getAllValues());

    Map<String, Long> lots = new HashMap<>(shape.lots());
    for (PaperService.OrderRequest request : requests) {
      String exchange =
          request.exchange() != null ? request.exchange() : shape.row().exchange();
      String tradingsymbol =
          request.tradingsymbol() != null ? request.tradingsymbol() : shape.row().tradingsymbol();
      long lot = lots.getOrDefault(exchange + "/" + tradingsymbol, 0L);
      if (lot <= 0 || request.qty() % lot != 0) {
        return true;
      }
    }
    return false;
  }
}
