package in.arthayantra.marketdata.watchlists;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
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
    assertThat(response.getBody()).containsEntry("name", "My Watchlist");
  }
}
