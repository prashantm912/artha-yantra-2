package in.arthayantra.marketdata.options;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.upstox.StockChainCandleSource;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient.Bar;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The ON-DEMAND stock-chain OI warm (audit §9.3, Wave 5 — owner decision 2026-07-02): instead of a
 * standing 210-chain capture, a stock's interval OI is fetched WHEN ASKED — one day of 1-minute
 * OHLCV+OI bars per option leg (live-probed: Upstox serves OI on unexpired contracts on both the
 * intraday and historical endpoints) plus the cash equity's bars as the spot series — derived into
 * {@code options_chain_snapshots} rows ({@code source='UPSTOX_1M'}), so EVERY existing OI reader
 * (chain Δ, trending, spurt, heatmap, big-OI, session log) serves the stock unchanged.
 *
 * <p>Semantics: a 1-minute bar stamped at its START covers [start, start+1m) and its OI is the
 * state at the bar END — rows persist at {@code start+1m}, so the readers' end-of-window labelling
 * (T2, ts−1s) puts each reading in the window it terminates, identical to the index capture and
 * the candle-derived history. {@code oi_change} is the bar-over-bar delta (first bar null).
 * IV/greeks stay null (same fidelity note as derived history). Inserts are PK-idempotent, so a
 * re-warm mid-session only tops up NEW buckets.
 *
 * <p>One warm runs at a time (single-thread executor) — a chain is ~2 calls/strike against the
 * shared Upstox 2000/30-min limiter; serialising keeps a warm from starving the backfill self-heal.
 */
@Service
public class StockChainWarmService {

  private static final Logger log = LoggerFactory.getLogger(StockChainWarmService.class);

  /** The async warm state for one (underlying, expiry, day). */
  public record WarmStatus(
      String underlying,
      LocalDate expiry,
      LocalDate day,
      String state, // UNAVAILABLE | IDLE | RUNNING | DONE | FAILED
      int totalLegs,
      int fetchedLegs,
      long rowsInStore) {}

  private record WarmKey(String underlying, LocalDate expiry, LocalDate day) {}

  /** Mutable in-flight counters (package-visible so the IT can drive the synchronous core). */
  static final class Progress {
    volatile String state = "RUNNING";
    volatile int total;
    volatile int fetched;
  }

  private final JdbcTemplate jdbc;
  private final OptionsSnapshotRepository repository;
  private final StockChainCandleSource source;
  private final Clock clock;
  private final Map<WarmKey, Progress> inFlight = new ConcurrentHashMap<>();
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "stock-chain-warm");
            t.setDaemon(true);
            return t;
          });

  public StockChainWarmService(
      JdbcTemplate jdbc,
      OptionsSnapshotRepository repository,
      StockChainCandleSource source,
      Clock clock) {
    this.jdbc = jdbc;
    this.repository = repository;
    this.source = source;
    this.clock = clock;
  }

  /** True when the Upstox candle stack is wired (live profile + analytics token). */
  public boolean available() {
    return source.available();
  }

  /** Starts an async warm (no-op when the same warm is already running); returns its status. */
  public WarmStatus start(String underlying, LocalDate expiry, LocalDate day) {
    if (!available()) {
      return status(underlying, expiry, day);
    }
    WarmKey key = new WarmKey(underlying, expiry, day);
    Progress fresh = new Progress();
    Progress existing = inFlight.putIfAbsent(key, fresh);
    if (existing != null && "RUNNING".equals(existing.state)) {
      return status(underlying, expiry, day); // single-flight
    }
    if (existing != null) {
      inFlight.put(key, fresh); // re-run after DONE/FAILED (incremental top-up)
    }
    executor.submit(
        () -> {
          try {
            warm(underlying, expiry, day, fresh);
            fresh.state = "DONE";
          } catch (RuntimeException e) {
            fresh.state = "FAILED";
            log.warn("stock-chain warm {} {} {} failed: {}", underlying, expiry, day, e.toString());
          }
        });
    return status(underlying, expiry, day);
  }

  /** The current status: in-flight progress when running, else the persisted row count. */
  public WarmStatus status(String underlying, LocalDate expiry, LocalDate day) {
    long rows = rowCount(underlying, expiry, day);
    if (!available()) {
      return new WarmStatus(underlying, expiry, day, "UNAVAILABLE", 0, 0, rows);
    }
    Progress p = inFlight.get(new WarmKey(underlying, expiry, day));
    String state = p == null ? "IDLE" : p.state;
    return new WarmStatus(
        underlying, expiry, day, state, p == null ? 0 : p.total, p == null ? 0 : p.fetched, rows);
  }

  /** The SYNCHRONOUS warm core (also the IT's entry point); returns rows persisted this run. */
  int warm(String underlying, LocalDate expiry, LocalDate day, Progress progress) {
    boolean today = day.equals(clock.instant().atZone(Ist.ZONE).toLocalDate());
    List<LegRef> legs = legs(underlying, expiry);
    progress.total = legs.size() + 1; // +1 = the equity spot series

    Map<OffsetDateTime, BigDecimal> spotAt = spotSeries(underlying, day, today);
    progress.fetched = 1;

    int persisted = 0;
    for (LegRef leg : legs) {
      List<Bar> bars = source.optionDayBars(underlying, leg.optionType(), expiry, leg.strike(), day, today);
      List<OptionsSnapshotRepository.SnapshotRow> rows = toRows(underlying, expiry, leg, bars, spotAt);
      if (!rows.isEmpty()) {
        persisted += repository.insertUpstoxDerived(rows);
      }
      progress.fetched++;
    }
    log.info(
        "stock-chain warm {} {} {}: {} legs, {} rows persisted", underlying, expiry, day, legs.size(), persisted);
    return persisted;
  }

  /** One leg's bars → snapshot rows: ts = bar END, oi_change = bar-over-bar delta, IV/greeks null. */
  static List<OptionsSnapshotRepository.SnapshotRow> toRows(
      String underlying,
      LocalDate expiry,
      LegRef leg,
      List<Bar> bars,
      Map<OffsetDateTime, BigDecimal> spotAt) {
    List<Bar> asc = new ArrayList<>(bars);
    asc.sort(Comparator.comparing(Bar::bucket)); // the endpoints return newest-first
    List<OptionsSnapshotRepository.SnapshotRow> rows = new ArrayList<>(asc.size());
    Long prevOi = null;
    for (Bar bar : asc) {
      OffsetDateTime ts = bar.bucket().plusMinutes(1); // state at bar END (T2 end-of-window)
      Long oiChange = bar.oi() == null || prevOi == null ? null : bar.oi() - prevOi;
      rows.add(
          new OptionsSnapshotRepository.SnapshotRow(
              ts,
              underlying,
              expiry,
              leg.strike(),
              leg.optionType(),
              leg.tradingsymbol(),
              bar.close(),
              null,
              null,
              bar.volume(),
              bar.oi(),
              oiChange,
              spotAt.get(ts),
              null,
              null,
              null,
              null,
              null,
              null,
              "UPSTOX_1M_DERIVED",
              "UPSTOX_1M",
              null,
              null));
      prevOi = bar.oi() != null ? bar.oi() : prevOi;
    }
    return rows;
  }

  /** The stock's listed option legs for the expiry, from the canonical instruments master. */
  record LegRef(String tradingsymbol, BigDecimal strike, String optionType) {}

  private List<LegRef> legs(String underlying, LocalDate expiry) {
    return jdbc.query(
        "SELECT tradingsymbol, strike, instrument_type FROM instruments "
            + "WHERE exchange = 'NFO' AND name = ? AND expiry = ? "
            + "AND instrument_type IN ('CE','PE') AND is_active ORDER BY strike, instrument_type",
        (rs, n) ->
            new LegRef(
                rs.getString("tradingsymbol"),
                rs.getBigDecimal("strike"),
                rs.getString("instrument_type")),
        underlying,
        java.sql.Date.valueOf(expiry));
  }

  /** The front (nearest on/after today) listed option expiry for the stock, or null. */
  public LocalDate frontExpiry(String underlying) {
    List<LocalDate> dates =
        jdbc.query(
            "SELECT min(expiry) AS e FROM instruments WHERE exchange = 'NFO' AND name = ? "
                + "AND instrument_type IN ('CE','PE') AND is_active AND expiry >= ?",
            (rs, n) -> rs.getObject("e", LocalDate.class),
            underlying,
            java.sql.Date.valueOf(clock.instant().atZone(Ist.ZONE).toLocalDate()));
    return dates.isEmpty() ? null : dates.get(0);
  }

  /** The equity 1m bars as a bar-END-keyed spot map (empty when the symbol doesn't resolve). */
  private Map<OffsetDateTime, BigDecimal> spotSeries(String symbol, LocalDate day, boolean today) {
    Map<OffsetDateTime, BigDecimal> map = new HashMap<>();
    for (Bar bar : source.equityDayBars(symbol, day, today)) {
      map.put(bar.bucket().plusMinutes(1), bar.close());
    }
    return map;
  }

  private long rowCount(String underlying, LocalDate expiry, LocalDate day) {
    OffsetDateTime from = day.atStartOfDay().atOffset(Ist.OFFSET);
    OffsetDateTime to = day.plusDays(1).atStartOfDay().atOffset(Ist.OFFSET);
    Long n =
        jdbc.queryForObject(
            "SELECT count(*) FROM options_chain_snapshots "
                + "WHERE underlying = ? AND expiry = ? AND ts >= ? AND ts < ?",
            Long.class,
            underlying,
            java.sql.Date.valueOf(expiry),
            java.sql.Timestamp.from(from.toInstant()),
            java.sql.Timestamp.from(to.toInstant()));
    return n == null ? 0 : n;
  }
}
