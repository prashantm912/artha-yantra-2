package in.arthayantra.marketdata.constituents;

import java.util.List;
import java.util.Optional;

/**
 * S8 port: one index's CURRENT membership from its source. Mock = the bundled NSE-format CSV
 * fixture (credential-free path); live = the current NSE published constituent CSV.
 *
 * <p>Survivorship-bias honesty clause (§C-2.15): NSE publishes the CURRENT list only, so
 * membership history exists from capture start — never before.
 */
public interface IndexConstituentsFetcher {

  /** One member of an index. */
  record Constituent(String exchange, String tradingsymbol) {}

  /** Current membership for {@code indexName}; empty when this source doesn't know the index. */
  Optional<List<Constituent>> fetch(String indexName);
}
