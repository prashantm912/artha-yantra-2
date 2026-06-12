package in.arthayantra.marketdata.watchlists;

import in.arthayantra.common.web.error.ConflictException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Watchlist CRUD (Phase 17 / B-1): items validate against the instrument master (unknown refs
 * never pass through toward Kite), duplicate adds are idempotent, deletes cascade.
 */
@RestController
@RequestMapping("/api/v1/watchlists")
public class WatchlistController {

  /** Create/rename request. */
  public record NameRequest(String name) {}

  /** Item request. */
  public record ItemRequest(String exchange, String tradingsymbol) {}

  /** One watchlist with items. */
  public record WatchlistView(
      UUID id, String name, OffsetDateTime createdAt, List<ItemRequest> items) {}

  private final JdbcTemplate jdbc;
  private final InstrumentRepository instruments;

  /** Wires the store. */
  public WatchlistController(JdbcTemplate jdbc, InstrumentRepository instruments) {
    this.jdbc = jdbc;
    this.instruments = instruments;
  }

  /** Creates a watchlist; duplicate names are 409. */
  @PostMapping
  public ResponseEntity<Map<String, Object>> create(@RequestBody NameRequest request) {
    UUID id = UUID.randomUUID();
    try {
      jdbc.update("INSERT INTO watchlists (id, name) VALUES (?, ?)", id, request.name());
    } catch (DuplicateKeyException e) {
      throw new ConflictException(
          ErrorCodes.CONFLICT_WATCHLIST_NAME, "watchlist '" + request.name() + "' already exists");
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("id", id, "name", request.name()));
  }

  /** All watchlists with their items. */
  @GetMapping
  public Map<String, List<WatchlistView>> list() {
    List<WatchlistView> lists =
        jdbc.query(
            "SELECT id, name, created_at FROM watchlists ORDER BY name",
            (rs, n) ->
                new WatchlistView(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    List.of()));
    return Map.of("items", lists.stream().map(w -> withItems(w.id(), w)).toList());
  }

  /** One watchlist with items; 404 when unknown. */
  @GetMapping("/{id}")
  public WatchlistView get(@PathVariable UUID id) {
    return withItems(id, find(id));
  }

  /** Deletes a watchlist (items cascade). */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    find(id);
    jdbc.update("DELETE FROM watchlists WHERE id = ?", id);
    return ResponseEntity.noContent().build();
  }

  /** Adds an item — validated against the master, duplicate-idempotent. */
  @PostMapping("/{id}/items")
  public WatchlistView addItem(@PathVariable UUID id, @RequestBody ItemRequest item) {
    find(id);
    if (instruments.findByKey(item.exchange(), item.tradingsymbol()).isEmpty()) {
      throw new NotFoundException(
          ErrorCodes.NOT_FOUND_INSTRUMENT,
          "unknown instrument " + item.exchange() + ":" + item.tradingsymbol());
    }
    jdbc.update(
        """
        INSERT INTO watchlist_items (watchlist_id, exchange, tradingsymbol)
        VALUES (?,?,?) ON CONFLICT DO NOTHING
        """,
        id, item.exchange(), item.tradingsymbol());
    return withItems(id, find(id));
  }

  /** Removes one item. */
  @DeleteMapping("/{id}/items")
  public ResponseEntity<Void> removeItem(
      @PathVariable UUID id, @RequestParam String exchange, @RequestParam String tradingsymbol) {
    find(id);
    jdbc.update(
        "DELETE FROM watchlist_items WHERE watchlist_id = ? AND exchange = ? AND tradingsymbol = ?",
        id, exchange, tradingsymbol);
    return ResponseEntity.noContent().build();
  }

  private WatchlistView find(UUID id) {
    List<WatchlistView> rows =
        jdbc.query(
            "SELECT id, name, created_at FROM watchlists WHERE id = ?",
            (rs, n) ->
                new WatchlistView(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    List.of()),
            id);
    if (rows.isEmpty()) {
      throw new NotFoundException(ErrorCodes.NOT_FOUND_WATCHLIST, "no watchlist " + id);
    }
    return rows.get(0);
  }

  private WatchlistView withItems(UUID id, WatchlistView base) {
    List<ItemRequest> items =
        jdbc.query(
            "SELECT exchange, tradingsymbol FROM watchlist_items WHERE watchlist_id = ?"
                + " ORDER BY added_at",
            (rs, n) -> new ItemRequest(rs.getString("exchange"), rs.getString("tradingsymbol")),
            id);
    return new WatchlistView(base.id(), base.name(), base.createdAt(), items);
  }
}
