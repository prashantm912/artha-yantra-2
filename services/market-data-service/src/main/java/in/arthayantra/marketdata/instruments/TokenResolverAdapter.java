package in.arthayantra.marketdata.instruments;

import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Master-backed implementation of the kite-side token resolver seam. */
@Component
public class TokenResolverAdapter implements InstrumentTokenResolver {

  private static final Logger log = LoggerFactory.getLogger(TokenResolverAdapter.class);

  /** Kite's canonical NSE tradingsymbol for a BE-series stock. */
  private static final String BE_SUFFIX = "-BE";

  private static final String NSE = "NSE";

  private final InstrumentRepository repository;
  private final Counter beFallbacks;

  /** Wires the master. */
  public TokenResolverAdapter(InstrumentRepository repository, MeterRegistry meters) {
    this.repository = repository;
    this.beFallbacks =
        Counter.builder("ay_instrument_be_suffix_fallback_total")
            .description("NSE token resolutions that succeeded only via the -BE suffixed symbol")
            .register(meters);
  }

  /**
   * Resolves a stable key to its Kite token, falling back to the {@code -BE} suffixed symbol on NSE.
   *
   * <p>⚠️ <b>Why the fallback exists (ledger H29).</b> Kite's canonical NSE tradingsymbol for a
   * BE-series stock carries a {@code -BE} suffix; bhavcopy, the screeners and the swing books all
   * use the BARE symbol. So {@code marketdata.instruments} holds BOTH — a tokenless bare row and a
   * tokened {@code <SYM>-BE} row — and this resolver only ever looked up the bare one. Measured
   * 2026-08-19: 304 NSE rows carry no token, and <b>27 of them have a {@code -BE} twin that does</b>
   * (exactly the 27 still trading). The token was there the whole time.
   *
   * <p>What that cost: {@code LiveHistoricalCandleGateway} threw {@code unknown instrument NSE:<sym>}
   * and fail-softed to stale cached data, silently. {@code KANORICHEM} and {@code AUTOIND} failed
   * this way every session — and both are held by OPEN swing paper positions (13, 34, 36).
   *
   * <p>⚠️ The fallback does NOT write anything. Copying the {@code -BE} token onto the bare row was
   * considered and rejected: it would put the same {@code instrument_token} on two rows, which the
   * symbol-normalization doctrine forbids, and it would go stale the moment a symbol leaves the BE
   * series. The {@code -BE} row is already kept current by the master sync — this just reads it.
   *
   * <p>The counter is the point as much as the resolution is: the original failure was invisible
   * because it fail-softed. A silent SUCCESS would repeat that mistake, so every fallback is counted
   * and the first one per symbol is logged.
   */
  @Override
  public Optional<TokenInfo> resolve(InstrumentKey key) {
    Optional<TokenInfo> direct = lookup(key.exchange(), key.tradingsymbol());
    if (direct.isPresent() || !isBeFallbackCandidate(key)) {
      return direct;
    }
    Optional<TokenInfo> viaBe = lookup(NSE, key.tradingsymbol() + BE_SUFFIX);
    if (viaBe.isPresent()) {
      beFallbacks.increment();
      log.info(
          "instrument {}:{} resolved via its {} twin — the bare row carries no Kite token (H29)",
          key.exchange(), key.tradingsymbol(), BE_SUFFIX);
    }
    return viaBe;
  }

  /**
   * NSE only, and never for a symbol that already carries the suffix — {@code FOO-BE-BE} is not a
   * thing, and letting it be tried would turn one miss into two queries on every unresolvable name.
   * BSE has no BE series, so the suffix means nothing there.
   */
  private static boolean isBeFallbackCandidate(InstrumentKey key) {
    return NSE.equals(key.exchange()) && !key.tradingsymbol().endsWith(BE_SUFFIX);
  }

  private Optional<TokenInfo> lookup(String exchange, String tradingsymbol) {
    return repository
        .findByKey(exchange, tradingsymbol)
        .filter(i -> i.instrumentToken() != null)
        .map(i -> new TokenInfo(i.instrumentToken(), i.instrumentType(), i.segment()));
  }
}
