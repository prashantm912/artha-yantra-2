package in.arthayantra.marketdata.screener.minervini.geometry;

import java.time.LocalDate;

/**
 * A confirmed zig-zag swing point (MV-5.1). {@code high=true} is a swing high (local peak),
 * {@code high=false} a swing low (local trough). {@code index} is the bar's position in the series
 * the pivot was extracted from.
 */
public record Pivot(int index, LocalDate date, double price, boolean high) {}
