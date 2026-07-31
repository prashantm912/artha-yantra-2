package in.arthayantra.marketdata.nse.analytics;

import in.arthayantra.marketdata.nse.analytics.NseEodReader.FiiDerivativeRow;
import in.arthayantra.marketdata.nse.analytics.NseEodReader.FiiDiiRow;
import in.arthayantra.marketdata.nse.analytics.NseEodReader.ParticipantOiRow;
import java.util.List;

/**
 * The four FII/DII list envelopes, typed so the contract gate can see them (ledger D3 slice 1).
 *
 * <p>PURE RETYPINGS of {@code Map.of("items", …)}. Every item type was ALREADY a record — three on
 * {@link NseEodReader}, and {@code LongShortRow} declared on the controller itself — so the envelope
 * was the only opaque part and the item shapes come along for free.
 *
 * <p>The source was {@code Map.of}, whose iteration order is unspecified, so no client could depend
 * on key order; a record additionally makes it deterministic. The key name ({@code items}) and the
 * value types are unchanged.
 */
public final class FiiDiiEnvelopes {

  private FiiDiiEnvelopes() {}

  /** {@code GET /api/v1/nse/fii-dii/cash} — daily FII/DII cash-segment flows. */
  public record Cash(List<FiiDiiRow> items) {}

  /** {@code GET /api/v1/nse/fii-dii/derivative-stats} — FII derivative open interest + flows. */
  public record DerivativeStats(List<FiiDerivativeRow> items) {}

  /** {@code GET /api/v1/nse/fii-dii/participant-oi} — per-participant OI by instrument class. */
  public record ParticipantOi(List<ParticipantOiRow> items) {}

  /** {@code GET /api/v1/nse/fii-dii/long-short} — the derived FII long/short ratio series. */
  public record LongShort(List<FiiDiiController.LongShortRow> items) {}
}
