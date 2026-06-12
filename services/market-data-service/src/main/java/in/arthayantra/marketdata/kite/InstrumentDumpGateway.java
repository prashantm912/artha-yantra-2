package in.arthayantra.marketdata.kite;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Port 5/5 (A.7a): the daily instrument dump. Live = Kite instruments CSV (~100k rows, Stage B,
 * Phase 9); mock = the committed CD-14 fixture (indices, top stocks, one NFO expiry ladder).
 */
public interface InstrumentDumpGateway {

  /** One dump row. */
  record InstrumentRecord(
      long instrumentToken,
      String exchange,
      String tradingsymbol,
      String name,
      String instrumentType,
      String segment,
      LocalDate expiry,
      BigDecimal strike,
      int lotSize,
      BigDecimal tickSize) {

    /** The stable identity of this instrument. */
    public InstrumentKey key() {
      return new InstrumentKey(exchange, tradingsymbol);
    }
  }

  /** The full dump. */
  List<InstrumentRecord> fetchDump();
}
