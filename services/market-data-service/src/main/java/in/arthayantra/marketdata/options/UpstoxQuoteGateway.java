package in.arthayantra.marketdata.options;

import in.arthayantra.marketdata.constituents.StockUpstoxKeyMap;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.upstox.UpstoxFnoMasterClient;
import in.arthayantra.marketdata.upstox.UpstoxQuoteClient;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Direct-Upstox-backed {@link QuoteGateway} (Wave U2). Bound ONLY when {@code
 * artha.marketdata.source.quotes=upstox} (and requires {@code artha.upstox.analytics.enabled=true},
 * which provides the {@link UpstoxQuoteClient}); absent ⇒ the Kite {@code LiveQuoteGateway} stays the
 * bound default (the unchanged fallback). On the long-lived 1-yr analytics token, so spot/FUT quotes
 * survive a missed daily Kite login. Mirrors {@link UpstoxOptionChainQuoteSource}'s placement (the
 * adapter lives in {@code options}, the wire client in {@code upstox}) so the only module dependencies
 * are the existing {@code options → upstox} / {@code options → kite} / {@code options → constituents}
 * directions — and it consumes the client's exposed {@link UpstoxQuoteClient.Tick} record, not the
 * module-internal {@code upstox.wire} DTO, so no module-boundary violation.
 *
 * <p>Maps the requested {@link InstrumentKey}s to Upstox {@code instrument_key}s (indices + cash
 * equities, plus FUT/option via the {@link UpstoxFnoMasterClient} when present; {@link
 * UpstoxQuoteInstrumentKeys}), issues ONE batch {@code /v2/market-quote/quotes} call (no fan-out), and
 * maps each {@link UpstoxQuoteClient.Tick} back to the domain {@link Quote}. Keys with no Upstox
 * mapping are simply omitted from the batch, so the caller treats them as absent — matching the {@code
 * QuoteGateway} contract.
 *
 * <p>⚠️ <b>THERE IS NO KITE FALLBACK FOR THOSE OMITTED KEYS, and the sentence that used to stand
 * here said the opposite.</b> The source flags select MUTUALLY-EXCLUSIVE beans: this class is
 * {@code @ConditionalOnProperty(name = "artha.marketdata.source.quotes", havingValue = "upstox")}
 * and the Kite {@link in.arthayantra.marketdata.kite.live.LiveQuoteGateway} bean is the SAME
 * property with {@code havingValue = "kite", matchIfMissing = true}. So when this bean exists the
 * Kite one does NOT, this class holds no Kite delegate, and an unmapped key is SILENTLY ABSENT
 * rather than served by Kite. Kite is the bound DEFAULT, not a runtime fallback. The composite
 * primary+fallback that would make the old sentence true is UNBUILT — ledger row H26.
 * Audit EXT-04, corrected 2026-08-25.
 */
@Component
@Profile("live")
@ConditionalOnProperty(name = "artha.marketdata.source.quotes", havingValue = "upstox")
public class UpstoxQuoteGateway implements QuoteGateway {

  private static final Logger log = LoggerFactory.getLogger(UpstoxQuoteGateway.class);

  private final UpstoxQuoteClient client;
  private final UpstoxQuoteInstrumentKeys instrumentKeys;

  /**
   * @param client the Upstox direct market-quote client
   * @param stockKeys the existing symbol → {@code NSE_EQ|<ISIN>} reference (reused, not duplicated)
   * @param instruments the instrument master (re-resolves a FUT/option key to its structured leg)
   * @param fnoMaster the Upstox F&amp;O instrument master; {@code null} when not bound — then FUT/option
   *     stays unmapped (pre-U4 behavior) and the Kite path remains the source for it
   */
  @Autowired
  public UpstoxQuoteGateway(
      UpstoxQuoteClient client,
      StockUpstoxKeyMap stockKeys,
      InstrumentRepository instruments,
      @Autowired(required = false) @Nullable UpstoxFnoMasterClient fnoMaster) {
    this.client = client;
    UpstoxFnoInstrumentKeys fnoKeys =
        fnoMaster == null ? null : new UpstoxFnoInstrumentKeys(instruments, fnoMaster);
    this.instrumentKeys = new UpstoxQuoteInstrumentKeys(stockKeys, fnoKeys);
  }

  /** Index + equity only (the pre-U4 shape; F&amp;O stays unmapped). Used by unit tests. */
  public UpstoxQuoteGateway(UpstoxQuoteClient client, StockUpstoxKeyMap stockKeys) {
    this(client, stockKeys, null, null);
  }

  @Override
  public Map<InstrumentKey, Quote> quotes(Collection<InstrumentKey> keys) {
    // Map each domain key to its Upstox instrument_key, keeping the reverse link so the batch result
    // can be re-keyed back to the caller's InstrumentKey. Unmappable keys are dropped from the batch.
    Map<String, InstrumentKey> byUpstoxKey = new LinkedHashMap<>();
    List<String> batch = new ArrayList<>();
    for (InstrumentKey key : keys) {
      String upstoxKey = instrumentKeys.key(key);
      if (upstoxKey == null) {
        log.debug("no Upstox instrument_key for {} — left to the Kite quote path", key.canonical());
        continue;
      }
      // de-dup: first domain key wins if two map to the same Upstox key
      byUpstoxKey.putIfAbsent(upstoxKey, key);
      batch.add(upstoxKey);
    }
    if (batch.isEmpty()) {
      return Map.of();
    }

    Map<String, UpstoxQuoteClient.Tick> ticks = client.quotes(batch);
    Map<InstrumentKey, Quote> out = new LinkedHashMap<>();
    for (Map.Entry<String, UpstoxQuoteClient.Tick> entry : ticks.entrySet()) {
      InstrumentKey key = byUpstoxKey.get(entry.getKey());
      if (key != null) {
        out.put(key, toDomain(key, entry.getValue()));
      }
    }
    return out;
  }

  /**
   * Maps the client {@link UpstoxQuoteClient.Tick} to the domain {@link Quote}; a missing {@code
   * last_price} defaults to zero (the Kite/OpenAlgo idiom), and the day OHLC collapses to {@code null}
   * when every component is absent (e.g. an index with no depth/OHLC).
   */
  private static Quote toDomain(InstrumentKey key, UpstoxQuoteClient.Tick tick) {
    Quote.Ohlc ohlc =
        (tick.open() == null
                && tick.high() == null
                && tick.low() == null
                && tick.prevClose() == null)
            ? null
            : new Quote.Ohlc(tick.open(), tick.high(), tick.low(), tick.prevClose());
    return new Quote(
        key,
        tick.lastPrice() != null ? tick.lastPrice() : BigDecimal.ZERO,
        tick.bid(),
        tick.ask(),
        tick.volume(),
        tick.oi(),
        ohlc,
        tick.timestamp());
  }
}
