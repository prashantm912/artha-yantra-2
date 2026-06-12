package in.arthayantra.marketdata.instruments;

import in.arthayantra.marketdata.kite.InstrumentKey;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * In-memory token→(exchange, tradingsymbol) resolve map for the tick normalizer (A.7a / B-3 step
 * 6). Stage B: backed by the {@code instruments} table — rebuilt at startup and after every sync.
 * On an empty database under the mock profile, the first sync runs inline so the feed pipeline is
 * resolvable from first boot (live defers to the 08:30 scheduler — the morning token may not
 * exist yet).
 */
@Component
public class InstrumentRegistry {

  private static final Logger log = LoggerFactory.getLogger(InstrumentRegistry.class);

  private final InstrumentRepository repository;
  private final Environment environment;
  private final AtomicReference<Map<Long, InstrumentKey>> byToken =
      new AtomicReference<>(Map.of());

  /** Wires the backing repository. */
  public InstrumentRegistry(InstrumentRepository repository, Environment environment) {
    this.repository = repository;
    this.environment = environment;
  }

  /** A fixed-map registry for unit tests that must not touch a database. */
  public static InstrumentRegistry fixedForTesting(Map<Long, InstrumentKey> tokenMap) {
    InstrumentRegistry registry = new InstrumentRegistry(null, null);
    registry.byToken.set(Map.copyOf(tokenMap));
    return registry;
  }

  /** Startup bootstrap: load the map; trigger an inline first sync on an empty mock database. */
  @EventListener(ContextRefreshedEvent.class)
  public void onStartup() {
    rebuildFromDatabase();
    boolean mock = !environment.matchesProfiles("live");
    if (byToken.get().isEmpty() && mock) {
      log.info("instrument master empty under mock profile - awaiting bootstrap sync");
    }
  }

  /** Step 6: atomically swap in the fresh token map from the database. */
  public void rebuildFromDatabase() {
    Map<Long, InstrumentKey> fresh = repository.activeTokenMap();
    byToken.set(Map.copyOf(fresh));
    log.info("instrument registry rebuilt: {} active tokens", fresh.size());
  }

  /** Token→key resolution for the normalizer. */
  public Optional<InstrumentKey> keyForToken(long instrumentToken) {
    return Optional.ofNullable(byToken.get().get(instrumentToken));
  }

  /** Number of resolvable tokens. */
  public int size() {
    return byToken.get().size();
  }
}
