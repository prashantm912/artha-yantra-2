package in.arthayantra.marketdata.instruments;

import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Phase-9 REST surface (B-1 endpoint catalog, instruments rows). */
@RestController
@RequestMapping("/api/v1/instruments")
public class InstrumentsController {

  /** Paged response shape (COMMON §3: limit/offset paging). */
  public record Page<T>(List<T> items, long total, int limit, int offset) {}

  private final InstrumentRepository repository;
  private final InstrumentSyncService syncService;
  private final InstrumentLookups lookups;

  /** Wires repository + sync + cached lookups. */
  public InstrumentsController(
      InstrumentRepository repository, InstrumentSyncService syncService, InstrumentLookups lookups) {
    this.repository = repository;
    this.syncService = syncService;
    this.lookups = lookups;
  }

  /** Paged instrument list with filters. */
  @GetMapping
  public Page<Instrument> list(
      @RequestParam(required = false) String exchange,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int boundedLimit = Math.min(Math.max(limit, 1), 500);
    return new Page<>(
        repository.page(exchange, type, q, boundedLimit, Math.max(offset, 0)),
        repository.count(exchange, type, q),
        boundedLimit,
        Math.max(offset, 0));
  }

  /** Ranked typeahead search over the trigram index. */
  @GetMapping("/search")
  public List<Instrument> search(
      @RequestParam String q, @RequestParam(defaultValue = "20") int limit) {
    return repository.searchRanked(q, Math.min(Math.max(limit, 1), 100));
  }

  /** Option expiries for an underlying, from the master. */
  @GetMapping("/{underlying}/expiries")
  public List<LocalDate> expiries(@PathVariable String underlying) {
    return lookups.expiries(underlying);
  }

  /** Strikes for an underlying + expiry. */
  @GetMapping("/{underlying}/strikes")
  public List<BigDecimal> strikes(
      @PathVariable String underlying, @RequestParam LocalDate expiry) {
    return lookups.strikes(underlying, expiry);
  }

  /** Single instrument by stable key. */
  @GetMapping("/{exchange}/{tradingsymbol}")
  public Instrument byKey(@PathVariable String exchange, @PathVariable String tradingsymbol) {
    return repository
        .findByKey(exchange, tradingsymbol)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCodes.NOT_FOUND_INSTRUMENT,
                    "no instrument " + exchange + ":" + tradingsymbol));
  }

  /** On-demand full sync — 202 + jobId; async on the service executor (no jobs table). */
  @PostMapping("/sync")
  public ResponseEntity<Map<String, String>> sync() {
    String jobId = syncService.triggerAsync();
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("jobId", jobId));
  }

  /** Last sync audit. */
  @GetMapping("/sync/status")
  public InstrumentSyncService.SyncStatus syncStatus() {
    return syncService.status();
  }

  /** Caffeine-backed lookups (B-4: `expiries` 10 m), self-invocation-safe. */
  @Service
  public static class InstrumentLookups {
    private final InstrumentRepository repository;

    /** Wires the repository. */
    public InstrumentLookups(InstrumentRepository repository) {
      this.repository = repository;
    }

    /** Cached expiry list. */
    @Cacheable(cacheNames = "expiries", key = "'expiries:' + #underlying")
    public List<LocalDate> expiries(String underlying) {
      return repository.expiries(underlying);
    }

    /** Cached strike list. */
    @Cacheable(cacheNames = "expiries", key = "'strikes:' + #underlying + ':' + #expiry")
    public List<BigDecimal> strikes(String underlying, LocalDate expiry) {
      return repository.strikes(underlying, expiry);
    }
  }
}
