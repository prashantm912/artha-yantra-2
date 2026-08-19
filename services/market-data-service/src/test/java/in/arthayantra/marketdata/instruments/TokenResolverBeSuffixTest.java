package in.arthayantra.marketdata.instruments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver.TokenInfo;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ledger H29: Kite's canonical NSE tradingsymbol for a BE-series stock carries a {@code -BE}
 * suffix, and every consumer we own (bhavcopy, the screeners, the swing books) uses the BARE
 * symbol.
 *
 * <p>So {@code marketdata.instruments} holds BOTH rows and this resolver only ever read the bare
 * one. Measured 2026-08-19: 304 NSE rows carry no {@code instrument_token}, and <b>27 of them have
 * a {@code -BE} twin that does</b> — exactly the 27 still trading. {@code KANORICHEM} (twin token
 * 2510081) and {@code AUTOIND} (3612161) failed a candle gap-fetch every session as
 * {@code unknown instrument NSE:<sym>}, fail-softing to stale cached data, while OPEN swing paper
 * positions held both.
 *
 * <p>These pin the fallback AND its limits. The limits matter more than usual here: the fallback
 * must not fire on BSE (no BE series), must not recurse on an already-suffixed symbol, and must not
 * mask a genuine miss — the original defect was invisible precisely because a failure fail-softed,
 * and a silent SUCCESS would repeat that.
 */
class TokenResolverBeSuffixTest {

  private static final String NSE = "NSE";

  private final InstrumentRepository repository = mock(InstrumentRepository.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final TokenResolverAdapter resolver = new TokenResolverAdapter(repository, meters);

  /** A master row; {@code token == null} is the shell-row shape the bare symbols carry. */
  private static Instrument row(String exchange, String tradingsymbol, Long token) {
    return new Instrument(
        exchange, tradingsymbol, token, token == null ? null : tradingsymbol,
        token == null ? null : exchange, "EQ", null, null, null, null, null, null, token != null);
  }

  private double fallbackCount() {
    return meters.find("ay_instrument_be_suffix_fallback_total").counter().count();
  }

  @Test
  @DisplayName("a tokenless NSE row resolves through its -BE twin")
  void aBareSymbolWithNoTokenResolvesViaTheSuffixedTwin() {
    when(repository.findByKey(NSE, "KANORICHEM")).thenReturn(Optional.of(row(NSE, "KANORICHEM", null)));
    when(repository.findByKey(NSE, "KANORICHEM-BE"))
        .thenReturn(Optional.of(row(NSE, "KANORICHEM-BE", 2510081L)));

    Optional<TokenInfo> got = resolver.resolve(new InstrumentKey(NSE, "KANORICHEM"));

    assertThat(got).isPresent();
    assertThat(got.get().instrumentToken()).isEqualTo(2510081L);
    assertThat(fallbackCount())
        .as("the fallback must be COUNTED — the defect it fixes was invisible because it fail-softed")
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("a symbol with no row at all still resolves through its -BE twin")
  void anAbsentBareRowAlsoFallsBack() {
    // AUTOIND's bare row exists, but the fallback must not DEPEND on that — a symbol we have never
    // seen bare should behave identically, or the fix would be coupled to the shell rows' existence.
    when(repository.findByKey(NSE, "AUTOIND")).thenReturn(Optional.empty());
    when(repository.findByKey(NSE, "AUTOIND-BE")).thenReturn(Optional.of(row(NSE, "AUTOIND-BE", 3612161L)));

    assertThat(resolver.resolve(new InstrumentKey(NSE, "AUTOIND")))
        .map(TokenInfo::instrumentToken)
        .contains(3612161L);
  }

  @Test
  @DisplayName("a normal EQ symbol never touches the -BE path")
  void aResolvableSymbolIsNotSecondGuessed() {
    when(repository.findByKey(NSE, "RELIANCE")).thenReturn(Optional.of(row(NSE, "RELIANCE", 738561L)));

    assertThat(resolver.resolve(new InstrumentKey(NSE, "RELIANCE")))
        .map(TokenInfo::instrumentToken)
        .contains(738561L);
    verify(repository, never()).findByKey(NSE, "RELIANCE-BE");
    assertThat(fallbackCount()).isZero();
  }

  @Test
  @DisplayName("BSE has no BE series, so the suffix is never tried there")
  void bseIsNeverSuffixed() {
    when(repository.findByKey("BSE", "KANORICHEM")).thenReturn(Optional.of(row("BSE", "KANORICHEM", null)));

    assertThat(resolver.resolve(new InstrumentKey("BSE", "KANORICHEM"))).isEmpty();
    verify(repository, never()).findByKey("BSE", "KANORICHEM-BE");
    verify(repository, never()).findByKey(NSE, "KANORICHEM-BE");
    assertThat(fallbackCount()).isZero();
  }

  @Test
  @DisplayName("an already-suffixed symbol is not suffixed twice")
  void theFallbackDoesNotRecurse() {
    when(repository.findByKey(NSE, "KANORICHEM-BE")).thenReturn(Optional.of(row(NSE, "KANORICHEM-BE", null)));

    assertThat(resolver.resolve(new InstrumentKey(NSE, "KANORICHEM-BE"))).isEmpty();
    verify(repository, never()).findByKey(NSE, "KANORICHEM-BE-BE");
  }

  @Test
  @DisplayName("a genuine miss is still a miss — the fallback must not manufacture a resolution")
  void anUnresolvableSymbolStaysUnresolved() {
    // 277 of the 304 shells have NO twin: genuinely delisted. Those must keep failing, or the
    // fallback would turn a real absence into a silent success — the exact shape of the original bug.
    when(repository.findByKey(NSE, "QUINTEGRA")).thenReturn(Optional.of(row(NSE, "QUINTEGRA", null)));
    when(repository.findByKey(NSE, "QUINTEGRA-BE")).thenReturn(Optional.empty());

    assertThat(resolver.resolve(new InstrumentKey(NSE, "QUINTEGRA"))).isEmpty();
    assertThat(fallbackCount()).isZero();
  }
}
