package in.arthayantra.backtest.indicators;

import java.util.List;

/**
 * The {@code GET /api/v1/indicators} body: the indicator registry, in the standard {@code {items}}
 * list envelope every list endpoint uses.
 *
 * <p>A PURE RETYPING of {@code Map.of("items", …)} (ledger D3 slice 1). The item type
 * ({@link IndicatorSeriesService.IndicatorMeta}) was ALREADY typed, so the envelope was the only
 * untyped part — converting it makes the whole response enumerable to the contract gate rather than
 * leaving an {@code array of object} behind, which is why this one was worth taking and the
 * hero-zero diagnostics were not.
 */
public record IndicatorRegistry(List<IndicatorSeriesService.IndicatorMeta> items) {}
