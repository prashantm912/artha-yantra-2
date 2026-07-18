package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Reads the EXPIRY-SESSION settlement reference for a symbol — the last real close captured on a
 * given (past) trading session — used by the past-expiry recovery in {@link PaperExpiryService} to
 * settle a position that was stranded OPEN when its on-time settle refused. Deliberately NOT the live
 * last tick ({@link LastTickReader}): a derivative cash-settled at the EXPIRY-day spot, so a
 * later-session or current spot would settle it at the wrong price. Empty when that session's close
 * was never captured (the recovery then leaves the position durably OPEN + re-alerts, never
 * fabricated, never settled at a wrong-session spot).
 */
public interface ExpirySpotReader {

  /** The symbol's close on {@code session} (the expiry-day settlement spot), if it was captured. */
  Optional<BigDecimal> expirySessionClose(String exchange, String tradingsymbol, LocalDate session);
}
