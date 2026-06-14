package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategyengine.fills.FillSimulator;
import in.arthayantra.strategyengine.fills.FillSimulator.Brokerage;
import in.arthayantra.strategyengine.fills.FillSimulator.Fees;
import in.arthayantra.strategyengine.fills.FillSimulator.Fill;
import in.arthayantra.strategyengine.fills.FillSimulator.FillRequest;
import in.arthayantra.strategyengine.fills.FillSimulator.Slippage;
import in.arthayantra.strategyengine.fills.LtpSlippageV1;
import in.arthayantra.strategyengine.fills.Side;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

/**
 * Prices paper fills through the SAME {@link FillSimulator} the backtest replay links (the shared
 * {@code strategy-engine} JAR) — a paper-local fill path would make paper and backtest P&L diverge
 * by construction (the Phase 43 FAIL). Per-class brokerage matches the backtest cost convention
 * (options ₹20/lot, futures/equity 0.03% per side) with the pinned statutory {@code Fees.DEFAULTS}.
 */
@Service
public class PaperFillService {

  private static final BigDecimal OPTION_PER_LOT = new BigDecimal("20.00");
  private static final BigDecimal PCT_PER_SIDE = new BigDecimal("0.03");

  private final FillSimulator fills = new LtpSlippageV1();

  /** The implementation id stamped onto the fill-audit row (e.g. {@code ltp_slippage/v1}). */
  public String simulatorId() {
    return fills.id();
  }

  /** Prices an order with the per-class slippage fallback (live-order semantics). */
  public Fill fill(Side side, long qty, BigDecimal reference, InstrumentMeta meta) {
    return fills.simulate(request(side, qty, reference, meta, Slippage.NONE));
  }

  /**
   * Recovers the exact entry cash basis at close time: zero added slippage (the open slippage is
   * already baked into {@code avgEntryPrice}), so only the turnover + cost legs are recomputed.
   */
  public Fill costBasis(Side side, long qty, BigDecimal avgEntryPrice, InstrumentMeta meta) {
    return fills.simulate(request(side, qty, avgEntryPrice, meta, Slippage.ticks(0)));
  }

  private static FillRequest request(
      Side side, long qty, BigDecimal reference, InstrumentMeta meta, Slippage slippage) {
    Brokerage brokerage =
        switch (meta.instrumentClass()) {
          case OPTION -> new Brokerage(OPTION_PER_LOT, null);
          case FUTURE, EQUITY -> new Brokerage(null, PCT_PER_SIDE);
        };
    return new FillRequest(
        side,
        qty,
        meta.lotSize(),
        reference,
        meta.instrumentClass(),
        meta.tickSize(),
        null,
        slippage,
        brokerage,
        Fees.DEFAULTS);
  }
}
