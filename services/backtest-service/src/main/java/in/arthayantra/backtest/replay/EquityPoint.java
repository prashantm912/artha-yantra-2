package in.arthayantra.backtest.replay;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One mark-to-market equity sample at a primary bar close — the basis for periodic returns. */
public record EquityPoint(OffsetDateTime ts, BigDecimal equity) {}
