package in.arthayantra.marketdata.instruments;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A row of the instrument master (B-7) — exact decimals, stable-key identity. */
public record Instrument(
    String exchange,
    String tradingsymbol,
    Long instrumentToken,
    String name,
    String segment,
    String instrumentType,
    String underlyingExchange,
    String underlyingTradingsymbol,
    LocalDate expiry,
    BigDecimal strike,
    BigDecimal tickSize,
    Integer lotSize,
    boolean active) {}
