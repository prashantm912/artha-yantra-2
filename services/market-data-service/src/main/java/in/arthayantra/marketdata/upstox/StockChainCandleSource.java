package in.arthayantra.marketdata.upstox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The stock-chain warm's candle seam (audit §9.3, Wave 5): one day of 1-minute OHLCV+OI bars for an
 * F&O option leg (resolved to its Upstox key internally) and for the underlying cash equity (the
 * spot series). Implemented by {@link UpstoxStockChainCandleSource}; the warm service depends on
 * THIS so tests can can the bars without the Upstox stack.
 */
public interface StockChainCandleSource {

  /** False when the Upstox analytics stack is not wired (token absent / disabled) — warm refuses. */
  boolean available();

  /**
   * One day of 1-minute bars for an NFO option leg; {@code today} picks the intraday endpoint (the
   * historical one serves completed days only). Empty when the leg's Upstox key can't be resolved.
   */
  List<UpstoxExpiredInstrumentsClient.Bar> optionDayBars(
      String underlying, String optionType, LocalDate expiry, BigDecimal strike, LocalDate day, boolean today);

  /** One day of 1-minute bars for the underlying CASH equity (the spot series); empty when unresolvable. */
  List<UpstoxExpiredInstrumentsClient.Bar> equityDayBars(String symbol, LocalDate day, boolean today);
}
