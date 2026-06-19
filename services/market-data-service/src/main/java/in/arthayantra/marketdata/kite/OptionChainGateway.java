package in.arthayantra.marketdata.kite;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Port 4/5 (plan §4.5 / M0): a full option chain for one {@code (underlying, expiry)} fetched in a
 * SINGLE upstream call, carrying the load-bearing per-strike open interest. This is the OI-capture
 * path the OpenAlgo integration exists for — OpenAlgo's {@code /quotes} omits {@code oi}, so a chain
 * cannot be reconstructed by per-strike quote fan-out; only {@code /optionchain} carries it.
 *
 * <p>Venue-neutral by design: rows are keyed by {@code strike} (+ the CE/PE side) so a consumer
 * matches its own instrument list regardless of the venue's option-symbol grammar. IV/greeks are
 * NEVER carried here — they come from {@code libs/black76-math} only (plan §17.9). The Kite path has
 * no native chain endpoint and stays on per-strike {@link QuoteGateway} fan-out; this port is bound
 * only when {@code artha.marketdata.source.optionchain=openalgo} (the §17.11 cutover, gated on the
 * live OI-coverage canary).
 */
public interface OptionChainGateway {

  /** The option chain for {@code (underlying, expiry)}; an empty chain yields zero rows, never null. */
  Chain fetch(InstrumentKey underlying, LocalDate expiry);

  /** The computed chain: the underlying context plus the per-strike CE/PE rows. */
  record Chain(String underlying, LocalDate expiry, BigDecimal underlyingLtp, List<Row> rows) {

    /**
     * Σ(CE oi) + Σ(PE oi) across all strikes — the §17.11 OI-coverage signal. {@code sum &gt; 0}
     * means the venue actually fills per-strike OI; {@code 0} on a live F&amp;O chain is the
     * silent-corruption case the entry-gate canary blocks before any source flip.
     */
    public long totalOi() {
      long total = 0;
      for (Row row : rows) {
        if (row.ce() != null && row.ce().oi() != null) {
          total += row.ce().oi();
        }
        if (row.pe() != null && row.pe().oi() != null) {
          total += row.pe().oi();
        }
      }
      return total;
    }
  }

  /** One strike row: the strike plus its CE and PE legs (either side may be null). */
  record Row(BigDecimal strike, Leg ce, Leg pe) {}

  /** One option leg's raw quote fields incl. the per-strike {@code oi} (no IV/greeks — §17.9). */
  record Leg(BigDecimal ltp, BigDecimal bid, BigDecimal ask, Long volume, Long oi) {}
}
