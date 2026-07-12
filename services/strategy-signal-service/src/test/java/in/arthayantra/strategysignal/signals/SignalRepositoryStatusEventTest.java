package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit-proves the §7.2.1 status-event emission centralised in {@link SignalRepository} — the single
 * choke point covering every take/dismiss/expire caller. Mocked JDBC + event publisher (no DB): a
 * status event fires IFF the mutation actually changed a row, and the bulk sweep fans out one event
 * per swept id.
 */
class SignalRepositoryStatusEventTest {

  private final JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
  private final ApplicationEventPublisher events = Mockito.mock(ApplicationEventPublisher.class);
  private final SignalRepository repo = new SignalRepository(jdbc, new ObjectMapper(), events);

  @Test
  void transitionEmitsOnlyWhenARowChanged() {
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    assertThat(repo.transition(7L, "DISMISSED")).isTrue();
    verify(events).publishEvent(new SignalStatusChanged(7L, null, "DISMISSED"));

    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
    assertThat(repo.transition(8L, "DISMISSED")).isFalse();
    verify(events, never()).publishEvent(new SignalStatusChanged(8L, null, "DISMISSED"));
  }

  @Test
  void transitionIfCarriesFromAndOnlyEmitsOnAWonCas() {
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    assertThat(repo.transitionIf(3L, "ACTIVE", "TAKEN")).isTrue();
    verify(events).publishEvent(new SignalStatusChanged(3L, "ACTIVE", "TAKEN"));

    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
    assertThat(repo.transitionIf(4L, "ACTIVE", "TAKEN")).isFalse();
    verify(events, never()).publishEvent(new SignalStatusChanged(4L, "ACTIVE", "TAKEN"));
  }

  @Test
  void expireAllActiveFansOutOneEventPerSweptId() {
    when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(11L, 12L));
    assertThat(repo.expireAllActive()).isEqualTo(2);
    verify(events).publishEvent(new SignalStatusChanged(11L, "ACTIVE", "EXPIRED"));
    verify(events).publishEvent(new SignalStatusChanged(12L, "ACTIVE", "EXPIRED"));
  }
}
