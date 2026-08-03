package in.arthayantra.marketdata.kite.ticker;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PR #1251 ratchet: {@code artha.subscriptions.pinned-indices} is the only static declaration that can
 * put a CASH-EXCHANGE instrument on the WS ticker, and therefore the only one that can put an EQUITY
 * into the {@code ticks:last} hash that {@code PaperBracketEvaluator} prices paper positions from.
 *
 * <p>The guard lives in {@link PinnedIndicesSubscriber}'s CONSTRUCTOR rather than in a test that reads
 * the YAML — the {@code VcpMinBaseWeeksTripwireTest} precedent. That way it fires for the compiled-in
 * default, for an {@code application.yml} override and for any future {@code .env} passthrough alike,
 * in every context that boots the bean, instead of only where someone remembered to assert. It is a
 * ratchet over the DECLARED universe, deliberately not a runtime probe of Redis: a probe would only
 * fail in an environment a live ticker feeds, which is exactly the environment-dependence this
 * replaces.
 *
 * <p>Not covered, and stated so nobody over-reads it: subscriptions the live engine takes dynamically
 * from published strategy configs are declared nowhere static and cannot be ratcheted here.
 */
class PinnedIndicesEquityRatchetTest {

  private static PinnedIndicesSubscriber withPins(String... pins) {
    return new PinnedIndicesSubscriber(mock(SubscriptionRegistry.class), List.of(pins));
  }

  @Test
  void addingACashEquityToThePinListTripsAtConstruction() {
    // The literal guarded condition: an ordinary NSE cash equity declared onto the live tick feed.
    // RELIANCE is not special — any tradable symbol reaches the same branch.
    assertThatThrownBy(() -> withPins("NSE:NIFTY 50", "NSE:RELIANCE"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NSE:RELIANCE")
        .hasMessageContaining("PaperBracketEvaluator") // names the money path at risk
        .hasMessageContaining("#1251") // points at the reasoning
        .hasMessageContaining("2026-08-02-manas-exit-stop-doctrine.md"); // and the doctrine
  }

  @Test
  void aBseCashEquityTripsToo() {
    // BSE is the other cash exchange — the swing books hold NSE today, but the guard must not be
    // NSE-shaped, or the same exposure walks in through BFO's cash sibling.
    assertThatThrownBy(() -> withPins("BSE:SENSEX", "BSE:TATASTEEL"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("BSE:TATASTEEL");
  }

  @Test
  void theShippedLivePinListStaysSilent() {
    // application.yml's live-profile value, verbatim. If this ever trips, the ratchet has become
    // stricter than the configuration it guards and every market-data Spring context fails to boot.
    assertThatCode(
            () ->
                withPins(
                    "NSE:NIFTY 50",
                    "NSE:NIFTY BANK",
                    "NSE:NIFTY FIN SERVICE",
                    "NSE:NIFTY MID SELECT",
                    "BSE:SENSEX",
                    "BSE:BANKEX",
                    "NSE:INDIA VIX"))
        .doesNotThrowAnyException();
  }

  @Test
  void theCompiledInDefaultStaysSilent() {
    // The @Value fallback used by every non-live profile — a strictly smaller subset of the above.
    assertThatCode(() -> withPins("NSE:NIFTY 50", "NSE:NIFTY BANK", "NSE:INDIA VIX"))
        .doesNotThrowAnyException();
  }

  @Test
  void derivativeSegmentsAreNotTheRatchetsBusiness() {
    // NFO/BFO instruments cannot be a cash equity, so the guard must ignore them entirely — narrowing
    // it to the cash exchanges is what keeps it from firing on the futures/options pinners' work.
    assertThatCode(() -> withPins("NFO:NIFTY26AUGFUT", "BFO:SENSEX26AUG80000CE"))
        .doesNotThrowAnyException();
  }

  @Test
  void aMalformedEntryIsLeftToTheExistingWarnPath() {
    // ensurePinned() already logs and skips these; the ratchet must not turn a cosmetic config typo
    // into a boot failure, which would be the "too strong" failure mode for this guard.
    assertThatCode(() -> withPins("NIFTY 50", "", ":NIFTY BANK")).doesNotThrowAnyException();
  }
}
