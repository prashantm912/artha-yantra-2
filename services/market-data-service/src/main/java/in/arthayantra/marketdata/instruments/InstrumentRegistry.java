package in.arthayantra.marketdata.instruments;

import in.arthayantra.marketdata.kite.InstrumentDumpGateway;
import in.arthayantra.marketdata.kite.InstrumentDumpGateway.InstrumentRecord;
import in.arthayantra.marketdata.kite.InstrumentKey;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory instrument master (A.7a): the token→(exchange, tradingsymbol) map the normalizer
 * resolves through. Loaded once at startup from the bound {@link InstrumentDumpGateway} (fixture
 * in mock, Kite dump in live — Stage B persists this to the {@code marketdata.instruments}
 * table, Phase 9).
 */
@Component
public class InstrumentRegistry {

  private static final Logger log = LoggerFactory.getLogger(InstrumentRegistry.class);

  private final InstrumentDumpGateway dumpGateway;
  private final Map<Long, InstrumentRecord> byToken = new HashMap<>();
  private List<InstrumentRecord> all = List.of();

  public InstrumentRegistry(InstrumentDumpGateway dumpGateway) {
    this.dumpGateway = dumpGateway;
  }

  @PostConstruct
  void load() {
    all = List.copyOf(dumpGateway.fetchDump());
    for (InstrumentRecord record : all) {
      byToken.put(record.instrumentToken(), record);
    }
    log.info("instrument registry loaded: {} instruments", all.size());
  }

  /** Token→record resolution for the normalizer. */
  public Optional<InstrumentRecord> byToken(long instrumentToken) {
    return Optional.ofNullable(byToken.get(instrumentToken));
  }

  /** Identity for a token, when known. */
  public Optional<InstrumentKey> keyForToken(long instrumentToken) {
    return byToken(instrumentToken).map(InstrumentRecord::key);
  }

  /** All known instruments. */
  public List<InstrumentRecord> all() {
    return all;
  }
}
