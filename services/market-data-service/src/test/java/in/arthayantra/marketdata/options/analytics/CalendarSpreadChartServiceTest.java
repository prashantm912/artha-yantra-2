package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleQueryService;
import in.arthayantra.marketdata.candles.CandleQueryService.CandleRead;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.options.OptionsChainService;
import in.arthayantra.marketdata.options.analytics.CalendarSpreadChartService.CalendarSpreadChart;
import in.arthayantra.marketdata.options.analytics.CalendarSpreadChartService.SpreadCandle;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link CalendarSpreadChartService}: the per-interval spread candle is the NEAR leg
 * minus the FAR leg, with the difference-envelope high/low ({@code H = nearH − farL}, {@code L = nearL
 * − farH}). Each leg's 1m bars are folded into the session-aligned interval bucket; deps are mocked.
 */
class CalendarSpreadChartServiceTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final LocalDate SESSION = LocalDate.of(2026, 6, 26);
  private static final LocalDate NEAR = LocalDate.of(2026, 7, 7);
  private static final LocalDate FAR = LocalDate.of(2026, 7, 14);
  private static final BigDecimal STRIKE = new BigDecimal("25000");
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-26T06:00:00Z"), ZoneOffset.UTC);

  private static Instrument leg(String ts) {
    return new Instrument(
        "NFO", ts, 1L, "NIFTY", "NFO-OPT", "CE", "NSE", "NIFTY 50", NEAR, STRIKE,
        new BigDecimal("0.05"), 75, true);
  }

  private static Candle bar(int minute, String o, String h, String l, String c, long vol) {
    return new Candle(
        "NFO", "X", "1m",
        OffsetDateTime.of(2026, 6, 26, 9, 15, 0, 0, IST).plusMinutes(minute),
        new BigDecimal(o), new BigDecimal(h), new BigDecimal(l), new BigDecimal(c), vol, null, "KITE");
  }

  @Test
  void chartsTheNearMinusFarSpreadWithTheDifferenceEnvelope() {
    InstrumentRepository instruments = mock(InstrumentRepository.class);
    OptionsChainService chainService = mock(OptionsChainService.class);
    CandleQueryService candleQuery = mock(CandleQueryService.class);
    QuoteGateway quoteGateway = mock(QuoteGateway.class);

    Instrument nearLeg = leg("NEAR-CE");
    Instrument farLeg = leg("FAR-CE");
    when(chainService.resolveExpiry("NIFTY", NEAR)).thenReturn(NEAR);
    when(instruments.optionChain("NIFTY", NEAR)).thenReturn(List.of(nearLeg));
    when(instruments.optionChain("NIFTY", FAR)).thenReturn(List.of(farLeg));
    // near leg: two 1m bars in the 09:15 3m bucket → folded open 100 / high 115 / low 95 / close 108 / vol 30
    when(candleQuery.read(eq("NFO"), eq("NEAR-CE"), eq("1m"), any(), any()))
        .thenReturn(
            new CandleRead(
                List.of(bar(0, "100", "110", "95", "105", 10), bar(1, "105", "115", "100", "108", 20)),
                false,
                OffsetDateTime.now(CLOCK)));
    // far leg: folded open 120 / high 135 / low 115 / close 128 / vol 20
    when(candleQuery.read(eq("NFO"), eq("FAR-CE"), eq("1m"), any(), any()))
        .thenReturn(
            new CandleRead(
                List.of(bar(0, "120", "130", "115", "125", 5), bar(1, "125", "135", "118", "128", 15)),
                false,
                OffsetDateTime.now(CLOCK)));
    when(quoteGateway.quotes(any())).thenReturn(Map.of());

    CalendarSpreadChartService service =
        new CalendarSpreadChartService(instruments, chainService, candleQuery, quoteGateway, CLOCK);
    CalendarSpreadChart chart = service.chart("NIFTY", STRIKE, "CE", NEAR, FAR, 3, SESSION);

    assertThat(chart.optionType()).isEqualTo("CE");
    assertThat(chart.nearExpiry()).isEqualTo(NEAR);
    assertThat(chart.farExpiry()).isEqualTo(FAR);
    assertThat(chart.items()).hasSize(1);
    SpreadCandle s = chart.items().get(0);
    assertThat(s.open()).isEqualByComparingTo("-20"); // 100 − 120
    assertThat(s.high()).isEqualByComparingTo("0"); //   nearHigh 115 − farLow 115
    assertThat(s.low()).isEqualByComparingTo("-40"); //  nearLow 95 − farHigh 135
    assertThat(s.close()).isEqualByComparingTo("-20"); // 108 − 128
    assertThat(s.nearClose()).isEqualByComparingTo("108");
    assertThat(s.farClose()).isEqualByComparingTo("128");
    assertThat(s.volume()).isEqualTo(50);
  }

  @Test
  void rejectsAFarExpiryNotAfterNear() {
    InstrumentRepository instruments = mock(InstrumentRepository.class);
    OptionsChainService chainService = mock(OptionsChainService.class);
    when(chainService.resolveExpiry("NIFTY", NEAR)).thenReturn(NEAR);

    CalendarSpreadChartService service =
        new CalendarSpreadChartService(
            instruments, chainService, mock(CandleQueryService.class), mock(QuoteGateway.class), CLOCK);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.chart("NIFTY", STRIKE, "CE", NEAR, NEAR, 3, SESSION))
        .isInstanceOf(in.arthayantra.common.web.error.ApiException.class);
  }

  @Test
  void rejectsAnUnsupportedInterval() {
    CalendarSpreadChartService service =
        new CalendarSpreadChartService(
            mock(InstrumentRepository.class), mock(OptionsChainService.class),
            mock(CandleQueryService.class), mock(QuoteGateway.class), CLOCK);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.chart("NIFTY", STRIKE, "CE", NEAR, FAR, 7, SESSION))
        .isInstanceOf(in.arthayantra.common.web.error.ApiException.class);
  }
}
