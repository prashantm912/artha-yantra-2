package in.arthayantra.marketdata.upstox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link StockChainCandleSource} over the Upstox stack: the F&O leg resolves through the public
 * instrument master ({@link UpstoxFnoMasterClient}), the equity spot symbol through
 * {@link UpstoxEquityMasterClient}, and the bars come from {@link UpstoxExpiredInstrumentsClient}'s
 * active-instrument endpoints — v3 intraday for TODAY (the v2 historical serves completed days
 * only), v2 historical for past days. A profile-independent component (the warm endpoint must
 * answer "not configured" rather than 404 on mock): its collaborators are live-profile optional
 * beans behind ObjectProviders, so {@link #available()} is the fail-closed switch.
 */
@Component
public class UpstoxStockChainCandleSource implements StockChainCandleSource {

  private final ObjectProvider<UpstoxExpiredInstrumentsClient> candles;
  private final ObjectProvider<UpstoxFnoMasterClient> fnoMaster;
  private final ObjectProvider<UpstoxEquityMasterClient> equityMaster;

  public UpstoxStockChainCandleSource(
      ObjectProvider<UpstoxExpiredInstrumentsClient> candles,
      ObjectProvider<UpstoxFnoMasterClient> fnoMaster,
      ObjectProvider<UpstoxEquityMasterClient> equityMaster) {
    this.candles = candles;
    this.fnoMaster = fnoMaster;
    this.equityMaster = equityMaster;
  }

  @Override
  public boolean available() {
    return candles.getIfAvailable() != null && fnoMaster.getIfAvailable() != null;
  }

  @Override
  public List<UpstoxExpiredInstrumentsClient.Bar> optionDayBars(
      String underlying,
      String optionType,
      LocalDate expiry,
      BigDecimal strike,
      LocalDate day,
      boolean today) {
    UpstoxExpiredInstrumentsClient client = candles.getIfAvailable();
    UpstoxFnoMasterClient master = fnoMaster.getIfAvailable();
    if (client == null || master == null) {
      return List.of();
    }
    String key = master.keyFor("NFO", underlying, optionType, expiry, strike);
    if (key == null) {
      return List.of();
    }
    return today
        ? client.intradayCandles(key, "minutes", 1)
        : client.activeCandles(key, "1minute", day, day);
  }

  @Override
  public List<UpstoxExpiredInstrumentsClient.Bar> equityDayBars(
      String symbol, LocalDate day, boolean today) {
    UpstoxExpiredInstrumentsClient client = candles.getIfAvailable();
    UpstoxEquityMasterClient master = equityMaster.getIfAvailable();
    if (client == null || master == null) {
      return List.of();
    }
    String key = master.equityKey(symbol);
    if (key == null) {
      return List.of();
    }
    return today
        ? client.intradayCandles(key, "minutes", 1)
        : client.activeCandles(key, "1minute", day, day);
  }
}
