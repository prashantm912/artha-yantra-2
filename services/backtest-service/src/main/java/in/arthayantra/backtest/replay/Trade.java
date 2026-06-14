package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategyengine.fills.Side;
import in.arthayantra.strategyengine.fills.TouchBasis;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One closed (or open-at-end) replay trade — the queryable row that replaces v1's lossy results
 * blob. {@code touchBasis} records how the exit level was detected (§D.11 A9). {@code contributions}
 * carries the entry breakdown's per-indicator contributions. {@code exchange}/{@code tradingsymbol}
 * denormalize the run's instrument onto every row (a run is single-instrument) so trade marks /
 * deep-links carry the right symbol; {@code stopLoss}/{@code takeProfit} are the entry-time
 * protective levels (absolute prices, {@code null} when the strategy declares no such rule).
 */
public record Trade(
    int seq,
    Side side,
    long qty,
    OffsetDateTime entryTs,
    BigDecimal entryPrice,
    OffsetDateTime exitTs,
    BigDecimal exitPrice,
    BigDecimal pnl,
    BigDecimal pnlPct,
    String exitReason,
    int barsHeld,
    TouchBasis touchBasis,
    JsonNode contributions,
    String exchange,
    String tradingsymbol,
    BigDecimal stopLoss,
    BigDecimal takeProfit) {}
