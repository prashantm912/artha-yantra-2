package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.upstox.wire.UpstoxMarketQuote.Tick;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UpstoxQuoteReKeyTest {

  private static Tick tick(String instrumentToken, String ltp) {
    return new Tick(null, null, new BigDecimal(ltp), instrumentToken, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  @Test
  void indexKeyMatchesViaNormalizedResponseKey() {
    // Index instrument_key IS the symbol, so the response key normalize(|->:) matches the request.
    Map<String, Tick> data = new LinkedHashMap<>();
    data.put("NSE_INDEX:Nifty 50", tick("NSE_INDEX|Nifty 50", "23865.75"));
    Map<String, Tick> out = UpstoxMarketStatusClient.reKey(List.of("NSE_INDEX|Nifty 50"), data);
    assertThat(out).containsOnlyKeys("NSE_INDEX|Nifty 50");
    assertThat(out.get("NSE_INDEX|Nifty 50").lastPrice()).isEqualByComparingTo("23865.75");
  }

  @Test
  void foKeyMatchesViaInstrumentTokenNotResponseKey() {
    // F&O: request key is NSE_FO|<token>, but Upstox keys the response by NSE_FO:<symbol>. The
    // normalize(|->:) of the request ("NSE_FO:54451") does NOT match the response key — so the tick must
    // be recovered via its instrument_token (the request key it echoes). This is the live-found fix.
    Map<String, Tick> data = new LinkedHashMap<>();
    data.put("NSE_FO:HDFCBANK26JULFUT", tick("NSE_FO|54451", "1816.50"));
    Map<String, Tick> out = UpstoxMarketStatusClient.reKey(List.of("NSE_FO|54451"), data);
    assertThat(out).containsOnlyKeys("NSE_FO|54451");
    assertThat(out.get("NSE_FO|54451").lastPrice()).isEqualByComparingTo("1816.50");
  }

  @Test
  void dropsTicksThatMatchNoRequestedKey() {
    Map<String, Tick> data = new LinkedHashMap<>();
    data.put("NSE_FO:UNREQUESTED", tick("NSE_FO|99999", "1"));
    assertThat(UpstoxMarketStatusClient.reKey(List.of("NSE_FO|54451"), data)).isEmpty();
  }
}
