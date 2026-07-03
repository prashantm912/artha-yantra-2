package in.arthayantra.marketdata.margin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.margin.MarginController.MarginLeg;
import in.arthayantra.marketdata.margin.MarginController.MarginRequest;
import in.arthayantra.marketdata.margin.MarginController.MarginResponse;
import in.arthayantra.marketdata.upstox.UpstoxFnoMasterClient;
import in.arthayantra.marketdata.upstox.UpstoxMarginClient;
import in.arthayantra.marketdata.upstox.UpstoxMarginClient.MarginQuote;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit test for the fail-soft resolution + gating of {@link MarginController}: absent Upstox
 * collaborators (analytics off) → unpriced; an unresolvable leg → unpriced without calling Upstox;
 * the happy path resolves the key and maps the quote.
 */
class MarginControllerTest {

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> p = mock(ObjectProvider.class);
    when(p.getIfAvailable()).thenReturn(value);
    return p;
  }

  private static MarginLeg leg(String product) {
    return new MarginLeg(
        "NFO", "NIFTY", "CE", LocalDate.parse("2026-07-07"), new BigDecimal("25000"), 75, "SELL",
        product, null);
  }

  private static in.arthayantra.marketdata.instruments.InstrumentRepository instruments() {
    return mock(in.arthayantra.marketdata.instruments.InstrumentRepository.class);
  }

  @Test
  void unpricedWhenTheUpstoxTokenIsOff() {
    MarginController c = new MarginController(provider(null), provider(null), instruments());
    MarginResponse r = c.margin(new MarginRequest(List.of(leg("D"))));
    assertThat(r.priced()).isFalse();
    assertThat(r.unpricedReason()).contains("not configured");
  }

  @Test
  void unpricedWhenTheLegHasNoUpstoxInstrument() {
    UpstoxMarginClient client = mock(UpstoxMarginClient.class);
    UpstoxFnoMasterClient master = mock(UpstoxFnoMasterClient.class);
    when(master.keyFor(any(), any(), any(), any(), any())).thenReturn(null);

    MarginController c = new MarginController(provider(client), provider(master), instruments());
    MarginResponse r = c.margin(new MarginRequest(List.of(leg("D"))));

    assertThat(r.priced()).isFalse();
    assertThat(r.unpricedReason()).contains("no Upstox instrument");
    verify(client, never()).quote(any());
  }

  @Test
  void resolvesATradingsymbolLegViaTheInstrumentMaster() {
    UpstoxMarginClient client = mock(UpstoxMarginClient.class);
    UpstoxFnoMasterClient master = mock(UpstoxFnoMasterClient.class);
    in.arthayantra.marketdata.instruments.InstrumentRepository repo = instruments();
    when(repo.findByKey("NFO", "NIFTY2570725000CE"))
        .thenReturn(
            java.util.Optional.of(
                new in.arthayantra.marketdata.instruments.Instrument(
                    "NFO", "NIFTY2570725000CE", 1L, "NIFTY", "NFO-OPT", "CE", "NSE", "NIFTY 50",
                    LocalDate.parse("2026-07-07"), new BigDecimal("25000"), new BigDecimal("0.05"),
                    75, true)));
    when(master.keyFor(eq("NFO"), eq("NIFTY"), eq("CE"), any(), any())).thenReturn("NSE_FO|44454");
    when(client.quote(any()))
        .thenReturn(
            new MarginQuote(true, null, new BigDecimal("99381.1"), new BigDecimal("31554.38"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("130935.48"),
                new BigDecimal("130935.48"), new BigDecimal("130821.73")));

    // tradingsymbol-only leg (structured fields null) → resolved from the master, then priced
    MarginLeg symLeg =
        new MarginLeg("NFO", null, null, null, null, 65, "SELL", "D", "NIFTY2570725000CE");
    MarginController c = new MarginController(provider(client), provider(master), repo);
    MarginResponse r = c.margin(new MarginRequest(List.of(symLeg)));

    assertThat(r.priced()).isTrue();
    assertThat(r.spanMargin()).isEqualByComparingTo("99381.1");
    verify(repo).findByKey("NFO", "NIFTY2570725000CE");
  }

  @Test
  void resolvesTheKeyAndMapsTheQuoteWithProductDefaultingToNrml() {
    UpstoxMarginClient client = mock(UpstoxMarginClient.class);
    UpstoxFnoMasterClient master = mock(UpstoxFnoMasterClient.class);
    when(master.keyFor(eq("NFO"), eq("NIFTY"), eq("CE"), any(), any())).thenReturn("NSE_FO|44454");
    when(client.quote(any()))
        .thenReturn(
            new MarginQuote(
                true, null, new BigDecimal("337004.85"), new BigDecimal("35224.61"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("372229.45"),
                new BigDecimal("372229.45"), new BigDecimal("188604.45")));

    // product null → defaults to "D" (NRML, full SPAN — the conservative sizing choice)
    MarginController c = new MarginController(provider(client), provider(master), instruments());
    MarginResponse r = c.margin(new MarginRequest(List.of(leg(null))));

    assertThat(r.priced()).isTrue();
    assertThat(r.spanMargin()).isEqualByComparingTo("337004.85");
    assertThat(r.finalMargin()).isEqualByComparingTo("188604.45");

    org.mockito.ArgumentCaptor<List<UpstoxMarginClient.Leg>> captor =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(client).quote(captor.capture());
    assertThat(captor.getValue().get(0).product()).isEqualTo("D");
    assertThat(captor.getValue().get(0).instrumentKey()).isEqualTo("NSE_FO|44454");
  }
}
