package in.arthayantra.marketdata.futures.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.marketdata.constituents.StaticIndexConstituents;
import in.arthayantra.marketdata.kite.FuturesContractSource;
import in.arthayantra.marketdata.kite.FuturesContractSource.FutContract;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.kite.QuoteGateway.Quote;
import in.arthayantra.marketdata.kite.QuoteGateway.Quote.Ohlc;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OiBuzzServiceTest {

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-23T07:00:00Z"), ZoneOffset.UTC);
  private static final OffsetDateTime TS = OffsetDateTime.parse("2026-06-23T12:30:00+05:30");

  private final StaticIndexConstituents constituents = mock(StaticIndexConstituents.class);
  private final FuturesContractSource contracts = mock(FuturesContractSource.class);
  private final QuoteGateway quotes = mock(QuoteGateway.class);
  private final PrevDayFutureOiCache prevOiCache = mock(PrevDayFutureOiCache.class);
  private final OiBuzzService service =
      new OiBuzzService(constituents, contracts, quotes, prevOiCache, CLOCK);

  private static FutContract fut(String symbol) {
    return new FutContract(new InstrumentKey("NFO", symbol + "26JUNFUT"), LocalDate.of(2026, 6, 25));
  }

  private static Quote quote(FutContract c, String ltp, String prevClose, long oi) {
    return new Quote(
        c.key(),
        new BigDecimal(ltp),
        null,
        null,
        1000L,
        oi,
        new Ohlc(new BigDecimal("101"), new BigDecimal("120"), new BigDecimal("80"), new BigDecimal(prevClose)),
        TS);
  }

  @Test
  void buildsTilesWithAdvanceDeclineAndGainersFirst() {
    FutContract gain = fut("GAIN");
    FutContract lose = fut("LOSE");
    when(constituents.symbols("NIFTY 50")).thenReturn(List.of("GAIN", "LOSE", "NOFUT"));
    when(contracts.monthlyFutures(eq("GAIN"), any())).thenReturn(List.of(gain));
    when(contracts.monthlyFutures(eq("LOSE"), any())).thenReturn(List.of(lose));
    when(contracts.monthlyFutures(eq("NOFUT"), any())).thenReturn(List.of()); // skipped
    when(quotes.quotes(any()))
        .thenReturn(
            Map.of(
                gain.key(), quote(gain, "110", "100", 50_000L), // +10%
                lose.key(), quote(lose, "90", "100", 60_000L))); // -10%
    // GAIN: price up + OI up vs prev-day 40k → +10k → LONG_BUILDUP. LOSE: no cached prev OI → null interp.
    when(prevOiCache.lookup(any(), eq("GAIN"))).thenReturn(40_000L);

    OiBuzzService.Heatmap h = service.heatmap("NIFTY 50");

    assertThat(h.advance()).isEqualTo(1);
    assertThat(h.decline()).isEqualTo(1);
    assertThat(h.tiles()).hasSize(2); // NOFUT dropped (no future)
    // Gainers first.
    assertThat(h.tiles().get(0).symbol()).isEqualTo("GAIN");
    assertThat(h.tiles().get(0).changePct()).isEqualByComparingTo("10.00");
    assertThat(h.tiles().get(0).oi()).isEqualTo(50_000L);
    assertThat(h.tiles().get(0).oiChange()).isEqualTo(10_000L); // 50k − 40k
    assertThat(h.tiles().get(0).interpretation())
        .isEqualTo(in.arthayantra.marketdata.options.OiInterpretation.LONG_BUILDUP);
    assertThat(h.tiles().get(1).symbol()).isEqualTo("LOSE");
    assertThat(h.tiles().get(1).changePct()).isEqualByComparingTo("-10.00");
    assertThat(h.asOf()).isEqualTo(TS);
    verify(prevOiCache).ensureWarm(any(), any()); // background warm kicked
  }

  @Test
  void unknownIndexIsNotFound() {
    when(constituents.symbols("BOGUS")).thenReturn(List.of());
    assertThatThrownBy(() -> service.heatmap("BOGUS")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void noResolvedFuturesIsDataGap() {
    when(constituents.symbols("NIFTY 50")).thenReturn(List.of("NOFUT"));
    when(contracts.monthlyFutures(eq("NOFUT"), any())).thenReturn(List.of());
    assertThatThrownBy(() -> service.heatmap("NIFTY 50"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).httpStatus()).isEqualTo(422));
  }
}
