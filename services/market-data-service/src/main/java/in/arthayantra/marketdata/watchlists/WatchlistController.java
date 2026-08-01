package in.arthayantra.marketdata.watchlists;

import in.arthayantra.common.web.error.ApiException;
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

  /** Create request; PUT also accepts {@code sortOrder} (B-1 rename/reorder). */
  public record NameRequest(String name, Integer sortOrder) {}

  /** Item request. */
  public record ItemRequest(String exchange, String tradingsymbol) {}

  /** One watchlist with items. */
  public record WatchlistView(
      UUID id, String name, OffsetDateTime createdAt, List<ItemRequest> items) {}

  /**
   * The 201 body of {@link #create} (D3 — was a {@code Map<String, Object>}). Both components are
   * non-null: {@code id} is freshly minted and {@code name} is rejected with a 400 when blank, and
   * the pre-D3 {@code Map.of} would have thrown on either being null. Key order is NORMALISED, not
   * preserved — a 2-key {@code Map.of}'s iteration order is JVM-salted, so there was no stable
   * emitted order to preserve.
   */
  public record WatchlistCreated(UUID id, String name) {}

  private final JdbcTemplate jdbc;
  private final InstrumentRepository instruments;

  /** Wires the store. */
  public WatchlistController(JdbcTemplate jdbc, InstrumentRepository instruments) {
    this.jdbc = jdbc;
    this.instruments = instruments;
  }

  /** Creates a watchlist; duplicate names are 409. */
  @PostMapping
  public ResponseEntity<WatchlistCreated> create(@RequestBody NameRequest request) {
    if (request.name() == null || request.name().isBlank()) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "watchlist name is required");
    }
    UUID id = UUID.randomUUID();
    try {
      jdbc.update("INSERT INTO watchlists (id, name) VALUES (?, ?)", id, request.name());
    } catch (DuplicateKeyException e) {
      throw new ConflictException(
          ErrorCodes.CONFLICT_WATCHLIST_NAME, "watchlist '" + request.name() + "' already exists");
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new WatchlistCreated(id, request.name()));
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

  /** Renames / reorders a watchlist (B-1 PUT row); 404 unknown, 409 duplicate name. */
  @org.springframework.web.bind.annotation.PutMapping("/{id}")
  public WatchlistView update(@PathVariable UUID id, @RequestBody NameRequest request) {
    find(id);
    try {
      jdbc.update(
          "UPDATE watchlists SET name = COALESCE(?, name), sort_order = COALESCE(?, sort_order)"
              + " WHERE id = ?",
          request.name(), request.sortOrder(), id);
    } catch (DuplicateKeyException e) {
      throw new ConflictException(
          ErrorCodes.CONFLICT_WATCHLIST_NAME, "watchlist '" + request.name() + "' already exists");
    }
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

  /** Removes one item; 404 when it was not in the list (B-1). */
  @DeleteMapping("/{id}/items")
  public ResponseEntity<Void> removeItem(
      @PathVariable UUID id, @RequestParam String exchange, @RequestParam String tradingsymbol) {
    find(id);
    int removed =
        jdbc.update(
            "DELETE FROM watchlist_items WHERE watchlist_id = ? AND exchange = ? AND tradingsymbol = ?",
            id, exchange, tradingsymbol);
    if (removed == 0) {
      throw new NotFoundException(
          ErrorCodes.NOT_FOUND_INSTRUMENT,
          exchange + ":" + tradingsymbol + " is not in this watchlist");
    }
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
