package in.arthayantra.marketdata.feed;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.instruments.InstrumentRegistry;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.RawTick;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The single-writer normalizer (A.7.1): token → stable {@code (exchange, tradingsymbol)} key,
 * exact decimals untouched, exchange timestamps normalized to IST {@code +05:30}, and a
 * per-instrument monotonic {@code seq}. NOT thread-safe by design — exactly one thread (the
 * {@link FeedPipeline} loop) calls {@link #normalize}.
 */
@Component
public class TickNormalizer {

  private final InstrumentRegistry registry;
  private final Map<InstrumentKey, Long> sequences = new HashMap<>();

  public TickNormalizer(InstrumentRegistry registry) {
    this.registry = registry;
  }

  /** Normalizes one raw tick; empty when the token is unknown to the instrument master. */
  public Optional<NormalizedTick> normalize(RawTick raw) {
    return registry
        .keyForToken(raw.instrumentToken())
        .map(
            key -> {
              long seq = sequences.merge(key, 1L, Long::sum);
              return new NormalizedTick(
                  key.exchange(),
                  key.tradingsymbol(),
                  raw.lastPrice(),
                  raw.cumulativeDayVolume(),
                  raw.openInterest(),
                  raw.exchangeTimestamp().atOffset(Ist.OFFSET),
                  seq);
            });
  }
}
