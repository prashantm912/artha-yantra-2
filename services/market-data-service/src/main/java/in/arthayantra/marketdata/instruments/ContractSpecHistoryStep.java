package in.arthayantra.marketdata.instruments;

import in.arthayantra.common.web.time.Ist;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase 9A (B-20): ONE set-based diff between the sync staging table and the current master,
 * running between B-3 steps 3 and 4 — {@code FIRST_SEEN} rows for contracts new to the master,
 * {@code CHANGED} rows when lot/tick differ. Never a per-row Java loop, no Kite calls of its own,
 * written only from the sync pipeline (single-writer D10). Same-day re-syncs are idempotent
 * ({@code ON CONFLICT DO NOTHING} on the daily PK).
 */
@Component
public class ContractSpecHistoryStep implements InstrumentSyncService.SyncStep {

  private static final Logger log = LoggerFactory.getLogger(ContractSpecHistoryStep.class);

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final Counter changesCounter;

  /** Wires the diff statement. */
  public ContractSpecHistoryStep(JdbcTemplate jdbc, Clock clock, MeterRegistry meterRegistry) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.changesCounter = meterRegistry.counter("ay_contract_spec_changes_total");
  }

  @Override
  public void afterStaging() {
    LocalDate asOf = LocalDate.now(clock.withZone(Ist.ZONE));
    List<String> changeTypes =
        jdbc.query(
            """
            INSERT INTO contract_spec_history
              (exchange, tradingsymbol, as_of_date, lot_size, tick_size,
               change_type, prev_lot_size, prev_tick_size, recorded_at)
            SELECT s.exchange, s.tradingsymbol, ?, s.lot_size, s.tick_size,
                   CASE WHEN i.exchange IS NULL THEN 'FIRST_SEEN' ELSE 'CHANGED' END,
                   i.lot_size, i.tick_size, now()
            FROM instruments_staging s
            LEFT JOIN instruments i
              ON i.exchange = s.exchange AND i.tradingsymbol = s.tradingsymbol
            WHERE i.exchange IS NULL
               OR i.lot_size  IS DISTINCT FROM s.lot_size
               OR i.tick_size IS DISTINCT FROM s.tick_size
            ON CONFLICT (exchange, tradingsymbol, as_of_date) DO NOTHING
            RETURNING change_type
            """,
            (rs, n) -> rs.getString(1),
            Date.valueOf(asOf));
    long changed = changeTypes.stream().filter("CHANGED"::equals).count();
    changesCounter.increment((double) changed);
    if (!changeTypes.isEmpty()) {
      log.info(
          "contract-spec accrual {}: {} FIRST_SEEN, {} CHANGED",
          asOf,
          changeTypes.size() - changed,
          changed);
    }
  }
}
