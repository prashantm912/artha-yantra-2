package in.arthayantra.marketdata.options;

import in.arthayantra.marketdata.upstox.UpstoxOptionChainClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Direct-Upstox-backed {@link OptionChainQuoteSource} (Wave U1). Bound ONLY when {@code
 * artha.marketdata.source.optionchain=upstox} (and requires {@code
 * artha.upstox.analytics.enabled=true}, which provides the {@link UpstoxOptionChainClient}); absent ⇒
 * {@code OptionsChainService} keeps reading per-strike quotes from the Kite {@code QuoteGateway} (the
 * unchanged default). Mirrors {@code UpstoxOptionAnalyticsSource}'s placement (the adapter lives in
 * {@code options}, the wire client in {@code upstox}) so the only module dependency is the existing
 * {@code options → upstox} direction — and it consumes the client's exposed {@code Chain} record, not
 * the module-internal {@code upstox.wire} DTO, so no module-boundary violation.
 *
 * <p>Maps the Upstox chain to the {@link ChainQuotes} domain record (per-strike
 * LTP/bid/ask/volume/OI/prev_oi + spot). Upstox-computed greeks/IV are deliberately dropped — the
 * black76 pipeline stays the IV/greeks source of record (the Upstox greeks are a cross-check, not the
 * source). Any miss (unmapped underlying, empty Upstox data, transport/HTTP error) returns {@code
 * empty} so the chain falls through to its default behaviour.
 */
@Component
@Profile("live")
@ConditionalOnProperty(name = "artha.marketdata.source.optionchain", havingValue = "upstox")
public class UpstoxOptionChainQuoteSource implements OptionChainQuoteSource {

  private static final Logger log = LoggerFactory.getLogger(UpstoxOptionChainQuoteSource.class);

  /** Our underlying index symbol → the Upstox {@code instrument_key} (the same verified map as U2). */
  private static final Map<String, String> INSTRUMENT_KEYS =
      Map.of(
          "NIFTY 50", "NSE_INDEX|Nifty 50",
          "NIFTY BANK", "NSE_INDEX|Nifty Bank",
          "NIFTY FIN SERVICE", "NSE_INDEX|Nifty Fin Service",
          "NIFTY MID SELECT", "NSE_INDEX|NIFTY MID SELECT",
          "SENSEX", "BSE_INDEX|SENSEX",
          "BANKEX", "BSE_INDEX|BANKEX");

  private final UpstoxOptionChainClient client;

  /**
   * @param client the Upstox direct option-chain client
   */
  public UpstoxOptionChainQuoteSource(UpstoxOptionChainClient client) {
    this.client = client;
  }

  @Override
  public Optional<ChainQuotes> fetch(String underlying, LocalDate expiry) {
    String key = INSTRUMENT_KEYS.get(underlying);
    if (key == null) {
      log.warn(
          "no Upstox instrument_key for underlying '{}' — leaving chain to default source", underlying);
      return Optional.empty();
    }
    UpstoxOptionChainClient.Chain chain;
    try {
      chain = client.optionChain(key, expiry);
    } catch (RuntimeException failed) {
      log.warn(
          "Upstox option-chain fetch failed for {} {} ({}) — leaving chain to default source",
          underlying,
          expiry,
          failed.getMessage());
      return Optional.empty();
    }
    if (chain == null) {
      log.warn(
          "Upstox option-chain empty for {} {} — leaving chain to default source", underlying, expiry);
      return Optional.empty();
    }

    Map<BigDecimal, Leg> ce = new LinkedHashMap<>();
    Map<BigDecimal, Leg> pe = new LinkedHashMap<>();
    chain.ce().forEach((strike, leg) -> ce.put(strike, toLeg(leg)));
    chain.pe().forEach((strike, leg) -> pe.put(strike, toLeg(leg)));
    return Optional.of(new ChainQuotes(chain.spot(), ce, pe));
  }

  /** Maps the client's {@code Leg} to the port {@link Leg} (1:1 — the client already dropped greeks). */
  private static Leg toLeg(UpstoxOptionChainClient.Leg leg) {
    return new Leg(leg.ltp(), leg.bid(), leg.ask(), leg.volume(), leg.oi(), leg.prevOi());
  }
}
