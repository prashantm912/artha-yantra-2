package in.arthayantra.marketdata.openalgo.live;

import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway.Candle;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.OiHistorySource;
import in.arthayantra.marketdata.openalgo.OpenAlgoSymbols;
import java.time.Instant;
import java.util.List;

/**
 * OpenAlgo adapter for the {@link OiHistorySource} backfill port (data-foundation milestone, plan §2).
 * Thin wrapper over an {@link OpenAlgoHistoricalCandleGateway} pinned to the {@code 1m} interval — it
 * reuses that gateway's wire client (POST {@code /history}, per-bar OI in the response) but exposes the
 * backfill's OWN port type, so it never competes in the routed {@code HistoricalCandleGateway} bean
 * pool. Built unconditionally (not behind {@code source.candles}); gated only by the live profile +
 * {@code artha.openalgo.oi-backfill-enabled} in {@link OpenAlgoConfig}.
 *
 * <p>The backfill passes a {@link InstrumentKey} carrying the <b>Kite</b> tradingsymbol (from the
 * instruments table). OpenAlgo's F&amp;O grammar differs ({@code NIFTY30JUN2625000CE} vs Kite
 * {@code NIFTY26JUN25000CE}), and the weekly Kite encoding cannot be parsed back unambiguously, so each
 * F&amp;O key is re-resolved to its structured leg via {@link InstrumentRepository#findByKey} and the
 * OpenAlgo symbol rebuilt from those fields ({@link OpenAlgoSymbols}). Cash/index keys pass through
 * unchanged — {@link OpenAlgoExchange} (inside the gateway) handles their remapping.
 */
public final class OpenAlgoHistoryClient implements OiHistorySource {

  private final HistoricalCandleGateway delegate;
  private final InstrumentRepository instruments;

  /** Wraps an OpenAlgo {@code /history} gateway dedicated to the backfill path. */
  public OpenAlgoHistoryClient(HistoricalCandleGateway delegate, InstrumentRepository instruments) {
    this.delegate = delegate;
    this.instruments = instruments;
  }

  @Override
  public List<Candle> oneMinute(InstrumentKey key, Instant from, Instant to) {
    return delegate.fetch(toOpenAlgoFno(key), "1m", from, to);
  }

  /** Rebuilds an F&amp;O key in OpenAlgo's symbol grammar; non-F&amp;O (cash/index) keys pass through. */
  private InstrumentKey toOpenAlgoFno(InstrumentKey key) {
    return instruments
        .findByKey(key.exchange(), key.tradingsymbol())
        .map(OpenAlgoHistoryClient::fnoKey)
        .orElse(key);
  }

  private static InstrumentKey fnoKey(Instrument i) {
    String type = i.instrumentType();
    if ("CE".equals(type) || "PE".equals(type)) {
      return new InstrumentKey(
          i.exchange(), OpenAlgoSymbols.optionSymbol(i.name(), i.expiry(), i.strike(), type));
    }
    if ("FUT".equals(type)) {
      return new InstrumentKey(i.exchange(), OpenAlgoSymbols.futureSymbol(i.name(), i.expiry()));
    }
    return new InstrumentKey(i.exchange(), i.tradingsymbol());
  }
}
