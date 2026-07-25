package in.arthayantra.marketdata.canary;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One failing (or degraded) canary check: {@code check} names the family, {@code key} the subject
 * (instrument key or table), {@code status} AMBER/RED, {@code detail} the human line, {@code since}
 * the last-healthy instant when known.
 */
public record CheckResult(
    String check,
    String key,
    String status,
    String detail,
    @Schema(types = {"string", "null"}) String since) {}
