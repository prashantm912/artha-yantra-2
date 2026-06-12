package in.arthayantra.marketdata.instruments;

import in.arthayantra.marketdata.kite.FuturesContractSource;
import in.arthayantra.marketdata.kite.InstrumentKey;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/** Master-backed implementation of the kite-side futures-contract seam (Phase 15A). */
@Component
public class FuturesSourceAdapter implements FuturesContractSource {

  private final InstrumentRepository repository;

  /** Wires the master. */
  public FuturesSourceAdapter(InstrumentRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<FutContract> monthlyFutures(String underlying, LocalDate onOrAfter) {
    return repository.futures(underlying).stream()
        .filter(i -> !i.expiry().isBefore(onOrAfter))
        .map(i -> new FutContract(new InstrumentKey(i.exchange(), i.tradingsymbol()), i.expiry()))
        .toList();
  }
}
