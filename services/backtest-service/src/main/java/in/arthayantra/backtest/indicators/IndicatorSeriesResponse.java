package in.arthayantra.backtest.indicators;

import java.util.List;

/** The typed {@code {series:[...]}} envelope for an indicator-series response. */
public record IndicatorSeriesResponse(List<IndicatorSeriesService.NamedSeries> series) {}
