package in.arthayantra.strategysignal.signals;

import in.arthayantra.strategyengine.fills.FillSimulator;
import in.arthayantra.strategyengine.fills.FillSimulator.Brokerage;
import in.arthayantra.strategyengine.fills.FillSimulator.Fees;
import in.arthayantra.strategyengine.fills.FillSimulator.Fill;
import in.arthayantra.strategyengine.fills.FillSimulator.FillRequest;
import in.arthayantra.strategyengine.fills.FillSimulator.Slippage;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategyengine.fills.LtpSlippageV1;
import in.arthayantra.strategyengine.fills.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Cost-adjusted shadow PnL (roadmap F8): prices a shadow round trip (1-lot long premium, BUY at
 * entry LTP / SELL at exit LTP) through the SAME engine {@link FillSimulator} the paper ledger and
 * the backtest replay use — a shadow-local cost formula would diverge from executed-trade P&amp;L
 * by construction. Zero added slippage on both legs (the recorded LTPs ARE the shadow's fills;
 * statutory costs + brokerage are the only adjustment), options brokerage pinned to the same
 * ₹20/lot convention as {@code PaperFillService}.
 *
 * <p>Lot size comes from the instrument master ({@code GET /api/v1/instruments/{ex}/{ts}}), memoized
 * per contract — a tiny local copy of the paper slice's lookup (Modulith: signals cannot import
 * paper). A failed lookup yields NO cost adjustment (nulls) rather than a wrong-lot number.
 */
@Component
public class ShadowCostModel {

  private static final Logger log = LoggerFactory.getLogger(ShadowCostModel.class);
  private static final BigDecimal OPTION_PER_LOT = new BigDecimal("20.00");
  private static final BigDecimal TICK = new BigDecimal("0.05");

  /** The 1-lot round-trip outcome in INR; {@code null} fields when the lot size is unresolvable. */
  public record RoundTrip(BigDecimal cost, BigDecimal pnlNet) {
    static final RoundTrip UNPRICED = new RoundTrip(null, null);
  }

  private final FillSimulator fills = new LtpSlippageV1();
  private final RestClient restClient;
  private final Map<String, Long> lotSizes = new ConcurrentHashMap<>();

  /** Wires the market-data base URL for the lot-size lookup. */
  public ShadowCostModel(
      RestClient.Builder builder, @Value("${artha.marketdata.base-url}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
  }

  /**
   * Prices one closed shadow (long premium: BUY entry, SELL exit). Never throws; returns
   * {@link RoundTrip#UNPRICED} when the exit is priceless (STALE) or the lot size is unknown.
   */
  public RoundTrip price(String exchange, String tradingsymbol, BigDecimal entryLtp, BigDecimal exitLtp) {
    try {
      if (entryLtp == null || exitLtp == null) {
        return RoundTrip.UNPRICED;
      }
      Long lot = lotSize(exchange, tradingsymbol);
      if (lot == null || lot <= 0) {
        return RoundTrip.UNPRICED;
      }
      Fill buy = leg(Side.BUY, lot, entryLtp);
      Fill sell = leg(Side.SELL, lot, exitLtp);
      BigDecimal cost =
          buy.costs().total().add(sell.costs().total()).setScale(2, RoundingMode.HALF_UP);
      BigDecimal net = buy.netValue().add(sell.netValue()).setScale(2, RoundingMode.HALF_UP);
      return new RoundTrip(cost, net);
    } catch (RuntimeException e) {
      log.warn("shadow cost pricing failed for {}:{}: {}", exchange, tradingsymbol, e.toString());
      return RoundTrip.UNPRICED;
    }
  }

  private Fill leg(Side side, long lotSize, BigDecimal ltp) {
    return fills.simulate(
        new FillRequest(
            side, lotSize, lotSize, ltp, InstrumentClass.OPTION, TICK, null,
            Slippage.ticks(0), new Brokerage(OPTION_PER_LOT, null), Fees.DEFAULTS));
  }

  private Long lotSize(String exchange, String tradingsymbol) {
    return lotSizes.computeIfAbsent(
        exchange + ":" + tradingsymbol,
        key -> {
          try {
            InstrumentDto dto =
                restClient
                    .get()
                    .uri("/api/v1/instruments/{exchange}/{tradingsymbol}", exchange, tradingsymbol)
                    .retrieve()
                    .body(InstrumentDto.class);
            return dto == null || dto.lotSize() == null || dto.lotSize() <= 0 ? null : dto.lotSize();
          } catch (RuntimeException e) {
            log.warn("lot-size lookup failed for {} — shadow stays unpriced: {}", key, e.getMessage());
            return null;
          }
        });
  }

  /** The slice of the instrument-master row this model needs. */
  private record InstrumentDto(Long lotSize) {}
}
