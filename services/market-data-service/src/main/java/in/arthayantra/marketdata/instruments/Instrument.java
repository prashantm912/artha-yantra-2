package in.arthayantra.marketdata.instruments;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A row of the instrument master (B-7) — exact decimals, stable-key identity. */
public record Instrument(
    String exchange,
    String tradingsymbol,
    @Schema(types = {"integer", "null"}) Long instrumentToken,
    @Schema(types = {"string", "null"}) String name,
    @Schema(types = {"string", "null"}) String segment,
    @Schema(types = {"string", "null"}) String instrumentType,
    @Schema(types = {"string", "null"}) String underlyingExchange,
    @Schema(types = {"string", "null"}) String underlyingTradingsymbol,
    @Schema(types = {"string", "null"}) LocalDate expiry,
    @Schema(type = "string", types = {"string", "null"}) BigDecimal strike,
    @Schema(type = "string", types = {"string", "null"}) BigDecimal tickSize,
    @Schema(types = {"integer", "null"}) Integer lotSize,
    boolean active) {}
