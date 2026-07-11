package in.arthayantra.strategysignal.insights;

import java.math.BigDecimal;

/**
 * A per-book risk-heat reading (INT design §3.2 risk row) assembled by {@link BookHeatReader} from
 * the persisted paper-margin annotations — display-only, read-only. {@code heatPct} is the summed
 * per-position {@code margin_pct} (SPAN margin as % of book equity at open); {@code capPct} the
 * configured heat cap for the book ({@code null} when unset); {@code maxConcentration} the largest
 * count of open positions sharing an underlying+side (the §3.2 concentration term).
 *
 * @param heatPct summed open-position margin_pct, or {@code null} when no priced position exists
 * @param capPct the book's heat_cap_pct, or {@code null} when unset/disabled
 */
public record BookHeat(
    String book,
    int openPositions,
    BigDecimal heatPct,
    BigDecimal capPct,
    int maxConcentration,
    String concentrationLeg) {}
