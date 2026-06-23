package in.arthayantra.marketdata.futures.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

class OpenHighServiceTest {

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-23T07:00:00Z"), ZoneOffset.UTC);
  private static final OffsetDateTime TS = OffsetDateTime.parse("2026-06-23T12:30:00+05:30");

  private final StaticIndexConstituents constituents = mock(StaticIndexConstituents.class);
  private final FuturesContractSource contracts = mock(FuturesContractSource.class);
  private final QuoteGateway quotes = mock(QuoteGateway.class);
  private final OpenHighService service = new OpenHighService(constituents, contracts, quotes, CLOCK);

  private static FutContract fut(String symbol) {
    return new FutContract(new InstrumentKey("NFO", symbol + "26JUNFUT"), LocalDate.of(2026, 6, 25));
  }

  private static Quote q(FutContract c, String open, String high, String low, String ltp) {
    return new Quote(
        c.key(),
        new BigDecimal(ltp),
        null,
        null,
        1000L,
        1L,
        new Ohlc(new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal("99")),
        TS);
  }

  @Test
  void splitsOpenHighAndOpenLowWithFarPct() {
    FutContract oh = fut("OHST"); // open==high → bearish
    FutContract ol = fut("OLST"); // open==low → bullish
    FutContract mid = fut("MIDST"); // neither
    when(constituents.symbols("NIFTY 50")).thenReturn(List.of("OHST", "OLST", "MIDST"));
    when(contracts.monthlyFutures(eq("OHST"), any())).thenReturn(List.of(oh));
    when(contracts.monthlyFutures(eq("OLST"), any())).thenReturn(List.of(ol));
    when(contracts.monthlyFutures(eq("MIDST"), any())).thenReturn(List.of(mid));
    when(quotes.quotes(any()))
        .thenReturn(
            Map.of(
                oh.key(), q(oh, "100", "100", "95", "98"), // open==high; far = (98−100)/100 = −2.00
                ol.key(), q(ol, "100", "105", "100", "103"), // open==low; far = (103−100)/100 = +3.00
                mid.key(), q(mid, "100", "105", "95", "102"))); // neither

    OpenHighService.OpenHigh r = service.openHighLow("NIFTY 50");

    assertThat(r.openHigh()).hasSize(1);
    assertThat(r.openHigh().get(0).symbol()).isEqualTo("OHST");
    assertThat(r.openHigh().get(0).farPct()).isEqualByComparingTo("-2.00");
    assertThat(r.openLow()).hasSize(1);
    assertThat(r.openLow().get(0).symbol()).isEqualTo("OLST");
    assertThat(r.openLow().get(0).farPct()).isEqualByComparingTo("3.00");
    assertThat(r.asOf()).isEqualTo(TS);
  }

  @Test
  void unknownIndexIsNotFound() {
    when(constituents.symbols("BOGUS")).thenReturn(List.of());
    assertThatThrownBy(() -> service.openHighLow("BOGUS")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void noResolvedFuturesIsDataGap() {
    when(constituents.symbols("NIFTY 50")).thenReturn(List.of("NOFUT"));
    when(contracts.monthlyFutures(eq("NOFUT"), any())).thenReturn(List.of());
    assertThatThrownBy(() -> service.openHighLow("NIFTY 50"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).httpStatus()).isEqualTo(422));
  }
}
