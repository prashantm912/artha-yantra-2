package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * §8.5 SPAN-aware capital usage: the {@link MarginServiceClient} is consulted ONLY for futures &amp;
 * short options, and any miss (flag off / unreachable) falls back to the flat margin-pct. Long
 * options and equities never call SPAN. Pure unit test — no Spring, no network.
 */
class PaperAccountServiceMarginTest {

  private static final InstrumentMeta SHORT_OPT =
      new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 75);
  private static final InstrumentMeta FUTURE =
      new InstrumentMeta(InstrumentClass.FUTURE, new BigDecimal("0.05"), 75);
  private static final InstrumentMeta EQUITY =
      new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);

  /** Records every SPAN call and returns a configurable estimate (empty == fall back to flat-pct). */
  private static final class RecordingMargin implements MarginServiceClient {
    final List<String> calls = new ArrayList<>();
    Optional<MarginEstimate> next = Optional.empty();

    @Override
    public Optional<MarginEstimate> marginFor(
        String exchange, String tradingsymbol, String side, BigDecimal price, long qty) {
      calls.add(side + " " + exchange + ":" + tradingsymbol);
      return next;
    }
  }

  private PaperAccountService service(MarginServiceClient margin) {
    return new PaperAccountService(
        mock(PaperAccountRepository.class),
        mock(PaperPositionRepository.class),
        mock(LastTickReader.class),
        mock(InstrumentMetaClient.class),
        margin,
        Clock.systemUTC(),
        new BigDecimal("0.15"),
        new BigDecimal("0.12"));
  }

  @Test
  void shortOptionUsesSpanWhenAvailable() {
    RecordingMargin margin = new RecordingMargin();
    margin.next = Optional.of(new MarginServiceClient.MarginEstimate(new BigDecimal("87750.00")));
    PaperAccountService svc = service(margin);

    BigDecimal usage =
        svc.usageFor(SHORT_OPT, "NFO", "NIFTY26JUN23500PE", "SELL", new BigDecimal("110.00"), 75);

    assertThat(usage).isEqualByComparingTo("87750.00");
    assertThat(margin.calls).hasSize(1); // SPAN consulted for a short option
  }

  @Test
  void shortOptionFallsBackToFlatPctWhenSpanEmpty() {
    RecordingMargin margin = new RecordingMargin(); // next = empty (disabled/unreachable)
    PaperAccountService svc = service(margin);

    // flat-pct short-option = notional(110*75=8250) * 0.12 = 990.00
    BigDecimal usage =
        svc.usageFor(SHORT_OPT, "NFO", "NIFTY26JUN23500PE", "SELL", new BigDecimal("110.00"), 75);

    assertThat(usage).isEqualByComparingTo("990.00");
    assertThat(margin.calls).hasSize(1); // tried SPAN, then fell back
  }

  @Test
  void futureUsesSpanWhenAvailable() {
    RecordingMargin margin = new RecordingMargin();
    margin.next = Optional.of(new MarginServiceClient.MarginEstimate(new BigDecimal("120000.00")));
    PaperAccountService svc = service(margin);

    BigDecimal usage =
        svc.usageFor(FUTURE, "NFO", "NIFTY26JUNFUT", "BUY", new BigDecimal("23500.00"), 75);

    assertThat(usage).isEqualByComparingTo("120000.00");
    assertThat(margin.calls).hasSize(1);
  }

  @Test
  void longOptionNeverCallsSpanAndUsesPremium() {
    RecordingMargin margin = new RecordingMargin();
    PaperAccountService svc = service(margin);

    // long option usage = premium notional = 110 * 75 = 8250.00 (no SPAN call)
    BigDecimal usage =
        svc.usageFor(SHORT_OPT, "NFO", "NIFTY26JUN23500CE", "BUY", new BigDecimal("110.00"), 75);

    assertThat(usage).isEqualByComparingTo("8250.00");
    assertThat(margin.calls).isEmpty(); // a long option is never a SPAN candidate
  }

  @Test
  void equityNeverCallsSpanAndUsesNotional() {
    RecordingMargin margin = new RecordingMargin();
    PaperAccountService svc = service(margin);

    BigDecimal usage =
        svc.usageFor(EQUITY, "NSE", "RELIANCE", "BUY", new BigDecimal("100.00"), 10);

    assertThat(usage).isEqualByComparingTo("1000.00");
    assertThat(margin.calls).isEmpty();
  }
}
