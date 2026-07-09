package in.arthayantra.marketdata.kite.canary;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.kite.AccessTokenProvider;
import in.arthayantra.marketdata.kite.KiteCallExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.client.RestClient;

/**
 * Register §9-23: {@code maybeRunDaily} must NOT reserve the daily idempotency marker before it has a
 * live token. If it did, a pre-morning-ritual trigger would burn the day's slot — {@code runNow} skips
 * on the missing token, and the marker then blocks any retry after the token arrives, silently missing
 * the day's contract canary. Fixed clock at 2026-07-06 (a trading Monday) so the trading-day gate is
 * deterministic regardless of when the suite runs.
 */
class ContractCanaryUnitTest {

  private static final Clock TRADING_MONDAY =
      Clock.fixed(
          ZonedDateTime.of(2026, 7, 6, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant(),
          ZoneOffset.UTC);

  @SuppressWarnings("unchecked")
  private ContractCanary canary(AccessTokenProvider tokenProvider, ValueOperations<String, String> ops) {
    RestClient.Builder builder = mock(RestClient.Builder.class);
    when(builder.baseUrl(anyString())).thenReturn(builder);
    when(builder.build()).thenReturn(mock(RestClient.class));
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue()).thenReturn(ops);
    KiteCallExecutor executor = mock(KiteCallExecutor.class);
    doReturn("{}").when(executor).execute(any(), any());
    return new ContractCanary(
        builder,
        "http://kite.local",
        "api-key",
        tokenProvider,
        executor,
        redis,
        mock(NtfyClient.class),
        MarketCalendar.nse(),
        TRADING_MONDAY,
        new ObjectMapper(),
        new SimpleMeterRegistry());
  }

  @Test
  @SuppressWarnings("unchecked")
  void noTokenDefersWithoutBurningTheDailyMarker() {
    AccessTokenProvider noToken = mock(AccessTokenProvider.class);
    when(noToken.currentToken()).thenReturn(Optional.empty());
    ValueOperations<String, String> ops = mock(ValueOperations.class);

    canary(noToken, ops).maybeRunDaily();

    // The marker was NOT reserved, so a later trigger (once the token exists) can still run today.
    verify(ops, never()).setIfAbsent(anyString(), anyString());
  }

  @Test
  @SuppressWarnings("unchecked")
  void withTokenReservesTheMarkerAndRuns() {
    AccessTokenProvider withToken = mock(AccessTokenProvider.class);
    when(withToken.currentToken()).thenReturn(Optional.of("live-token"));
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(ops.setIfAbsent(anyString(), anyString())).thenReturn(true);

    canary(withToken, ops).maybeRunDaily();

    // Token present ⇒ it proceeds past the guard and reserves the day's marker.
    verify(ops).setIfAbsent(contains("kite:canary:2026-07-06"), eq("RUNNING"));
  }
}
