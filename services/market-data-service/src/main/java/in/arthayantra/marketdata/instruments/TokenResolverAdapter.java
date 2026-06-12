package in.arthayantra.marketdata.instruments;

import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Master-backed implementation of the kite-side token resolver seam. */
@Component
public class TokenResolverAdapter implements InstrumentTokenResolver {

  private final InstrumentRepository repository;

  /** Wires the master. */
  public TokenResolverAdapter(InstrumentRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<TokenInfo> resolve(InstrumentKey key) {
    return repository
        .findByKey(key.exchange(), key.tradingsymbol())
        .filter(i -> i.instrumentToken() != null)
        .map(i -> new TokenInfo(i.instrumentToken(), i.instrumentType()));
  }
}
