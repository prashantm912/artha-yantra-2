package in.arthayantra.marketdata.screener;

import java.time.LocalDate;

/**
 * The DEEP_SWING internal endpoint request (research-fidelity audit P0-3). {@code family} selects the
 * deep-sim ({@code manas} | {@code minervini}); {@code from} is the sim start date (the deep-sim is
 * date-based over {@code candles}@1d); {@code variant} picks the phased-grid variant to persist (null
 * ⇒ the family's primary full-filter variant). Called by the backtest-service DEEP_SWING worker.
 */
public record DeepSwingRunRequest(String family, LocalDate from, String variant) {}
