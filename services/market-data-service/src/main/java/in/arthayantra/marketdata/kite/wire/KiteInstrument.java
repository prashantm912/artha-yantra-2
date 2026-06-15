package in.arthayantra.marketdata.kite.wire;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the Kite instruments dump CSV ({@code GET /instruments/{exchange}}) — all twelve
 * documented columns in wire order. Built by the gateway's header-driven CSV parse (the dump is
 * CSV, not JSON), then mapped to the domain {@code InstrumentRecord}. {@code exchangeToken} and
 * {@code lastPrice} are mirrored for reference though the domain record does not carry them.
 */
public record KiteInstrument(
    long instrumentToken,
    long exchangeToken,
    String tradingsymbol,
    String name,
    BigDecimal lastPrice,
    LocalDate expiry,
    BigDecimal strike,
    BigDecimal tickSize,
    Integer lotSize,
    String instrumentType,
    String segment,
    String exchange) {}
