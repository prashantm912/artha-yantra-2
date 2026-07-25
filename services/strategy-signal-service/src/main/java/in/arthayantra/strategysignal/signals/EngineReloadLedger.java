package in.arthayantra.strategysignal.signals;

import java.time.Clock;
import java.sql.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * T15 (bug queue B7): persists each reload outcome to {@code strategy.engine_reloads} so the boot
 * line survives the deploy that would destroy its container log — the 2026-07-17 and 07-20
 * forensics both lost their headline root cause to exactly that race. Append-only, a handful of
 * rows per day, observability only: a ledger failure is swallowed (WARN) because the reload path
 * must never depend on it. Health reads {@code unresolved == 0}, never {@code loaded > 0} (a cold
 * boot produces a PARTIAL load — the 2026-07-16 F10 lesson).
 */
@Component
public class EngineReloadLedger {

  private static final Logger log = LoggerFactory.getLogger(EngineReloadLedger.class);

  private final JdbcTemplate jdbc;
  private final Clock clock;

  /** Wires the strategy-schema datasource + clock. */
  public EngineReloadLedger(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** One reload outcome; {@code installed} = banks cleared + resubscribed vs structurally unchanged. */
  public void record(int loaded, int unresolved, int loadErrors, boolean installed) {
    try {
      jdbc.update(
          "INSERT INTO engine_reloads (reload_at, loaded, unresolved, load_errors, installed)"
              + " VALUES (?, ?, ?, ?, ?)",
          Timestamp.from(clock.instant()), loaded, unresolved, loadErrors, installed);
    } catch (RuntimeException e) {
      log.warn("engine reload ledger write failed (reload unaffected): {}", e.toString());
    }
  }
}
