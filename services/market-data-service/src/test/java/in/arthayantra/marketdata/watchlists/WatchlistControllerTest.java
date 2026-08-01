package in.arthayantra.marketdata.watchlists;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class WatchlistControllerTest {

  private JdbcTemplate jdbc;
  private WatchlistController controller;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    controller = new WatchlistController(jdbc, mock(InstrumentRepository.class));
  }

  @Test
  void rejectsNullNameWith400AndNeverTouchesTheDb() {
    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> controller.create(new WatchlistController.NameRequest(null, null)));
    assertThat(ex.httpStatus()).isEqualTo(400);
    assertThat(ex.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
    verifyNoInteractions(jdbc);
  }

  @Test
  void rejectsBlankNameWith400() {
    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> controller.create(new WatchlistController.NameRequest("   ", null)));
    assertThat(ex.httpStatus()).isEqualTo(400);
    assertThat(ex.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
    verifyNoInteractions(jdbc);
  }

  @Test
  void acceptsAValidNameAndReturns201() {
    var response = controller.create(new WatchlistController.NameRequest("My Watchlist", null));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().name()).isEqualTo("My Watchlist");
    assertThat(response.getBody().id()).isNotNull();
  }

  /**
   * D3 wire guard: {@code create} used to return {@code ResponseEntity<Map<String, Object>>} — a
   * shape the contract gate cannot see AND one this ratchet's regex cannot even count (it matches
   * only a bare {@code public Map<String, Object>}, never a {@code ResponseEntity}-wrapped one). So
   * nothing anywhere pinned these two keys. The retyping to {@code WatchlistCreated} must emit the
   * SAME two keys with the same value types; only their ORDER is normalised, a 2-key {@code Map.of}
   * having had no stable iteration order to preserve.
   */
  @Test
  void theCreatedBodySerialisesToExactlyTheIdAndNameKeys() throws Exception {
    var body = controller.create(new WatchlistController.NameRequest("Wire Shape", null)).getBody();

    JsonNode json = new ObjectMapper().valueToTree(body);
    List<String> keys = new ArrayList<>();
    json.fieldNames().forEachRemaining(keys::add);

    assertThat(keys).containsExactlyInAnyOrder("id", "name");
    assertThat(json.get("name").asText()).isEqualTo("Wire Shape");
    assertThat(UUID.fromString(json.get("id").asText())).isNotNull(); // still a bare UUID string
  }
}
