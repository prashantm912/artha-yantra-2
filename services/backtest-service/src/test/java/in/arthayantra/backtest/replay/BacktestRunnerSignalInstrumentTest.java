package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategyengine.series.SeriesKey;
import org.junit.jupiter.api.Test;

/**
 * {@link BacktestRunner#signalInstrument} resolves the single signal instrument for the v1 engine. The
 * options/futures-of-underlying case carries the underlying as a schema-v1 instrumentRef OBJECT
 * {@code {exchange, tradingsymbol}} — the pre-2026-06-25 code only parsed a legacy {@code "EXCH:SYMBOL"}
 * string, so a schema-valid options strategy threw "needs an explicit single-instrument universe". This
 * pins all accepted shapes + the throw (caught live by the Part-2 value-verify). E1 §3.3: a
 * {@code futures_screener} config has NO instrument — its picks are pinned into the request universe
 * array at submission, so the signal is the TOP pinned pick.
 */
class BacktestRunnerSignalInstrumentTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final JsonNode noRequest = mapper.createObjectNode();

  private JsonNode cfg(String json) {
    try {
      return mapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void resolvesUnderlyingObjectForOptionsOfUnderlying() {
    SeriesKey k =
        BacktestRunner.signalInstrument(
            cfg(
                "{\"universe\":{\"mode\":\"options_of_underlying\","
                    + "\"underlying\":{\"exchange\":\"NSE\",\"tradingsymbol\":\"NIFTY 50\"}}}"),
            noRequest);
    assertThat(k.exchange()).isEqualTo("NSE");
    assertThat(k.tradingsymbol()).isEqualTo("NIFTY 50");
    assertThat(k.interval()).isEqualTo("1m");
  }

  @Test
  void prefersSignalUnderlyingOverrideForOptions() {
    // 2b-E2: signal on the NIFTY continuous front-future, execute on SENSEX option legs. The SIGNAL
    // instrument is the override; the option-execution root (universe.underlying) is resolved elsewhere.
    SeriesKey k =
        BacktestRunner.signalInstrument(
            cfg(
                "{\"universe\":{\"mode\":\"options_of_underlying\","
                    + "\"underlying\":{\"exchange\":\"BFO\",\"tradingsymbol\":\"SENSEX\"},"
                    + "\"signal_underlying\":{\"exchange\":\"NFO\",\"tradingsymbol\":\"NIFTY-FUT-CONT\"}}}"),
            noRequest);
    assertThat(k.exchange()).isEqualTo("NFO");
    assertThat(k.tradingsymbol()).isEqualTo("NIFTY-FUT-CONT");
    assertThat(k.interval()).isEqualTo("1m");
  }

  @Test
  void fallsBackToUnderlyingWhenNoSignalUnderlying() {
    // Absent signal_underlying → the signal IS the underlying (every existing config is unchanged).
    SeriesKey k =
        BacktestRunner.signalInstrument(
            cfg(
                "{\"universe\":{\"mode\":\"options_of_underlying\","
                    + "\"underlying\":{\"exchange\":\"NSE\",\"tradingsymbol\":\"NIFTY 50\"}}}"),
            noRequest);
    assertThat(k.tradingsymbol()).isEqualTo("NIFTY 50");
  }

  @Test
  void resolvesExplicitInstrumentsArray() {
    SeriesKey k =
        BacktestRunner.signalInstrument(
            cfg("{\"universe\":{\"instruments\":[{\"exchange\":\"NSE\",\"tradingsymbol\":\"RELIANCE\"}]}}"),
            noRequest);
    assertThat(k.tradingsymbol()).isEqualTo("RELIANCE");
  }

  @Test
  void resolvesLegacyColonStringUnderlying() {
    SeriesKey k =
        BacktestRunner.signalInstrument(
            cfg("{\"universe\":{\"underlying\":\"NSE:NIFTY 50\"}}"), noRequest);
    assertThat(k.exchange()).isEqualTo("NSE");
    assertThat(k.tradingsymbol()).isEqualTo("NIFTY 50");
  }

  @Test
  void futuresScreenerSignalsOnTheTopPinnedPick() {
    // E1 §3.3: the config has no instrument; the resolved picks are pinned into the REQUEST universe
    // array at submission. The runner is single-signal-instrument → it signals on the TOP pick.
    SeriesKey k =
        BacktestRunner.signalInstrument(
            cfg("{\"universe\":{\"mode\":\"futures_screener\",\"side\":\"long\",\"max_picks\":5}}"),
            cfg(
                "{\"universe\":[{\"exchange\":\"NFO\",\"tradingsymbol\":\"HDFCBANK26JULFUT\"},"
                    + "{\"exchange\":\"NFO\",\"tradingsymbol\":\"SBIN26JULFUT\"}]}"));
    assertThat(k.exchange()).isEqualTo("NFO");
    assertThat(k.tradingsymbol()).isEqualTo("HDFCBANK26JULFUT");
    assertThat(k.interval()).isEqualTo("1m");
  }

  @Test
  void futuresScreenerThrowsWhenSubmissionPinnedNoMovers() {
    assertThatThrownBy(
            () ->
                BacktestRunner.signalInstrument(
                    cfg("{\"universe\":{\"mode\":\"futures_screener\"}}"),
                    cfg("{\"universe\":[]}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pinned universe");
  }

  @Test
  void funnelModeSignalsOnTheTopPinnedPick() {
    // B9 (EVO §13 row 19): manas_arora_funnel / minervini_funnel have no instrument in the config —
    // the funnel's resolved constituents are pinned into the REQUEST universe array at submission
    // (JobsService), so the single-signal-instrument runner signals on the TOP pinned pick.
    SeriesKey k =
        BacktestRunner.signalInstrument(
            cfg("{\"universe\":{\"mode\":\"manas_arora_funnel\"}}"),
            cfg(
                "{\"universe\":[{\"exchange\":\"NSE\",\"tradingsymbol\":\"RELIANCE\"},"
                    + "{\"exchange\":\"NSE\",\"tradingsymbol\":\"TCS\"}]}"));
    assertThat(k.exchange()).isEqualTo("NSE");
    assertThat(k.tradingsymbol()).isEqualTo("RELIANCE");
    assertThat(k.interval()).isEqualTo("1m");
  }

  @Test
  void minerviniFunnelThrowsWhenSubmissionPinnedNoCandidates() {
    assertThatThrownBy(
            () ->
                BacktestRunner.signalInstrument(
                    cfg("{\"universe\":{\"mode\":\"minervini_funnel\"}}"),
                    cfg("{\"universe\":[]}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pinned universe");
  }

  @Test
  void strikeReferencePrefersTheOverrideElseTheSignal() {
    // 2b-E2b: strike_reference decouples the ATM-strike spot from the signal (e.g. SENSEX-fut anchor).
    SeriesKey signal = new SeriesKey("NFO", "NIFTY-FUT-CONT", "1m");
    SeriesKey withRef =
        BacktestRunner.strikeReferenceInstrument(
            cfg(
                "{\"universe\":{\"strike_reference\":"
                    + "{\"exchange\":\"BFO\",\"tradingsymbol\":\"SENSEX-FUT-CONT\"}}}"),
            signal);
    assertThat(withRef.exchange()).isEqualTo("BFO");
    assertThat(withRef.tradingsymbol()).isEqualTo("SENSEX-FUT-CONT");
    assertThat(withRef.interval()).isEqualTo("1m");

    // absent → the signal instrument itself (strike anchored on the signal series → parity-safe)
    assertThat(BacktestRunner.strikeReferenceInstrument(cfg("{\"universe\":{}}"), signal))
        .isEqualTo(signal);
  }

  @Test
  void throwsWhenNoResolvableInstrument() {
    assertThatThrownBy(
            () ->
                BacktestRunner.signalInstrument(cfg("{\"universe\":{\"mode\":\"index\"}}"), noRequest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("single-instrument universe");
  }

  // ---- P1-2 / audit B4: statutory instrument-class detection on the candle path ----

  @Test
  void classifiesFoSegmentFuturesAsFuture() {
    // A dated or continuous future on an F&O segment (NFO/BFO) is priced under the FUTURE stack.
    assertThat(BacktestRunner.signalInstrumentClass(new SeriesKey("NFO", "NIFTY-FUT-CONT", "1m")))
        .isEqualTo(InstrumentClass.FUTURE);
    assertThat(BacktestRunner.signalInstrumentClass(new SeriesKey("NFO", "HDFCBANK26JULFUT", "1m")))
        .isEqualTo(InstrumentClass.FUTURE);
    assertThat(BacktestRunner.signalInstrumentClass(new SeriesKey("bfo", "SENSEX-FUT-CONT", "1m")))
        .isEqualTo(InstrumentClass.FUTURE); // exchange match is case-insensitive
  }

  @Test
  void classifiesFoSegmentOptionsAsOption() {
    // Options never actually reach the candle path for real strategies (options_of_underlying routes
    // to the premium replay); this covers only an explicit NFO/BFO option-series universe instrument.
    // costs.instrumentClass REFUSES forcing OPTION (CostConfigTest.forcedOptionClassIsRefused).
    assertThat(BacktestRunner.signalInstrumentClass(new SeriesKey("NFO", "NIFTY26JUL24000CE", "1m")))
        .isEqualTo(InstrumentClass.OPTION);
    assertThat(BacktestRunner.signalInstrumentClass(new SeriesKey("BFO", "SENSEX26JUL80000PE", "1m")))
        .isEqualTo(InstrumentClass.OPTION);
  }

  @Test
  void classifiesCashAndIndexAsEquity() {
    // Cash equities and index spots on NSE/BSE keep the EQUITY-delivery stack — byte-identical to the
    // pre-P1-2 blanket default. Segment-gated: a cash symbol is never mis-read as a future.
    assertThat(BacktestRunner.signalInstrumentClass(new SeriesKey("NSE", "NIFTY 50", "1m")))
        .isEqualTo(InstrumentClass.EQUITY);
    assertThat(BacktestRunner.signalInstrumentClass(new SeriesKey("NSE", "RELIANCE", "1m")))
        .isEqualTo(InstrumentClass.EQUITY);
    assertThat(BacktestRunner.signalInstrumentClass(new SeriesKey("BSE", "SENSEX", "1m")))
        .isEqualTo(InstrumentClass.EQUITY);
  }

  /**
   * Review fix 1: an options_of_underlying run's net came from OptionsPremiumReplay's own OPTION
   * stack — the runner-level costConfig only classified the SIGNAL series (e.g. the NIFTY future the
   * strategy signals on) and priced NOTHING on that path. The provenance stamp must say OPTION, not
   * the signal-series class.
   */
  @Test
  void costClassStampsOptionOnThePremiumPath() {
    CostConfig signalSeriesConfig = CostConfig.forClass(InstrumentClass.FUTURE);
    assertThat(BacktestRunner.costClass(true, signalSeriesConfig)).isEqualTo("OPTION");
    assertThat(BacktestRunner.costClass(false, signalSeriesConfig)).isEqualTo("FUTURE");
    assertThat(BacktestRunner.costClass(false, CostConfig.defaults())).isEqualTo("EQUITY");
  }
}
