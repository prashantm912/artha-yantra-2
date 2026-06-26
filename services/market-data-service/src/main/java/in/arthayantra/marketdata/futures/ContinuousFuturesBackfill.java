package in.arthayantra.marketdata.futures;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Reconstructs the historical {@code {root}-FUT-CONT} 1m stitch for backtests (2b-E1). The live
 * 16:15 {@link ContinuousFuturesRoller} only sees the currently-listed contracts in {@code
 * instruments}, so a fresh window has no continuous-future signal before live capture started. This
 * builds the FULL front-month ladder — the {@code expired_contracts} FUT roster UNION the live
 * futures — and runs the same {@link ContinuousFuturesRoller#stitch stitch} loop over it, so the
 * front-month series spans every roll from the earliest expired contract through today.
 *
 * <p>Idempotent and additive: {@code stitchInto} is {@code ON CONFLICT DO NOTHING} and {@code
 * roll_events.append} dedupes on {@code (underlying, roll_date)}, so it never disturbs the live
 * roller or a prior run. Backtests read the UNADJUSTED CONT 1m directly; the documented roll-day
 * basis gap (B-19) is the one-bar discontinuity at each contract switch.
 */
@Service
public class ContinuousFuturesBackfill {

  private final InstrumentRepository instruments;
  private final ContinuousFuturesBackfillRepository expired;
  private final ContinuousFuturesRoller roller;
  private final Clock clock;

  /** Wires the live + expired rosters, the shared stitch loop, and the IST clock. */
  public ContinuousFuturesBackfill(
      InstrumentRepository instruments,
      ContinuousFuturesBackfillRepository expired,
      ContinuousFuturesRoller roller,
      Clock clock) {
    this.instruments = instruments;
    this.expired = expired;
    this.roller = roller;
    this.clock = clock;
  }

  /**
   * Reconstructs {@code {root}-FUT-CONT} from the expired + live front-month roster. {@code root} is
   * the dump root (e.g. {@code NIFTY}); {@code underlyingExchange}/{@code underlyingDisplay} stamp the
   * synthetic instrument + key {@code roll_events} (e.g. {@code NSE} / {@code NIFTY 50}). All three MUST
   * refer to the same underlying — {@code root} keys the {@code expired_contracts} roster while {@code
   * underlyingDisplay} keys the live futures, so a mismatched pair would merge two different ladders.
   * Returns the number of contracts in the merged ladder.
   */
  public int backfill(String root, String underlyingExchange, String underlyingDisplay) {
    Map<String, Instrument> bySymbol = new LinkedHashMap<>();
    for (var ef : expired.futuresRoster(root)) {
      bySymbol.put(
          ef.tradingsymbol(),
          new Instrument(
              ef.exchange(),
              ef.tradingsymbol(),
              null,
              root,
              null,
              "FUT",
              underlyingExchange,
              underlyingDisplay,
              ef.expiry(),
              null,
              null,
              null,
              true));
    }
    // Live (currently-listed) futures win on overlap — they carry the canonical instrument fields.
    for (Instrument live : instruments.futures(underlyingDisplay)) {
      if (!"SYN-CONT".equals(live.segment())) {
        bySymbol.put(live.tradingsymbol(), live);
      }
    }
    List<Instrument> ladder =
        bySymbol.values().stream()
            .filter(i -> i.expiry() != null)
            .sorted(Comparator.comparing(Instrument::expiry))
            .toList();
    if (ladder.isEmpty()) {
      return 0;
    }
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    // refreshAggregates=false: stitching the whole history at once would refresh a wide cagg range
    // (~106k expired contracts' mid-interval buckets) — minutes-long + OOM-risky on the live instance.
    // Backtests read CONT 1m from the base table, so the 1m stitch is sufficient (see stitch() Javadoc).
    roller.stitch(ladder, underlyingDisplay, today, false);
    return ladder.size();
  }
}
