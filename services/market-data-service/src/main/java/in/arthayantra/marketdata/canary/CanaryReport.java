package in.arthayantra.marketdata.canary;

import java.util.List;

/**
 * One canary evaluation: worst-of {@code status} (GREEN/AMBER/RED), whether the session gate was
 * open (all checks are session-gated — a closed market is always GREEN), the count of instruments
 * with any tick this run, and the non-GREEN check results (capped at
 * {@link DataHealthCanary#REPORT_PROBLEM_CAP}).
 */
public record CanaryReport(
    String status, boolean marketOpen, String asOf, int tickedTokens, List<CheckResult> problems) {}
