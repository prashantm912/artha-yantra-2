package in.arthayantra.backtest.replay.options;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Resolves which option contract a directional signal trades (§D.6 {@code options_of_underlying}), for
 * premium-as-primary replay (Part 2). At each entry the underlying spot picks the nearest-listed ATM
 * strike, the schema's {@code expiry} rule picks the expiry, and the bias picks the side — long buys
 * CE, short buys PE (premium-buying only, per the frozen schema + the canonical NIFTY-ATM-scalper
 * example). The contract universe is the backfilled {@code expired_contracts} registry, read through a
 * {@link Catalog} so the selection logic is unit-testable without a DB.
 *
 * <p>The contract is resolved PER ENTRY — ATM-at-entry moves with spot, so two trades of the same
 * strategy can trade different strikes. {@code atm_window} width does not multiply legs here: a single
 * directional scalp trades the ATM strike (the window bounds the loaded data, not the tradeable leg).
 */
@Component
public class OptionContractSelector {

  /** The schema's {@code expiry} rule. {@code OFFSET} carries the Nth-out index (0 = nearest). */
  public enum ExpiryMode {
    NEAREST_WEEKLY,
    NEAREST_MONTHLY,
    OFFSET
  }

  /** One resolved expiry candidate from the registry. */
  public record Expiry(LocalDate date, boolean weekly) {}

  /** The selected contract — the key into {@code candles} + its lot size for sizing. */
  public record OptionContract(
      String exchange,
      String tradingsymbol,
      BigDecimal strike,
      LocalDate expiry,
      String optionType,
      int lotSize) {}

  /** Read access to the backfilled expired-contract registry (DB-backed in prod, faked in tests). */
  public interface Catalog {
    /** Distinct expiries for the underlying on/after {@code date}, ascending. */
    List<Expiry> expiriesOnOrAfter(String underlying, LocalDate date);

    /** The nearest-strike contract for {@code (underlying, expiry, optionType)} to {@code spot}. */
    Optional<OptionContract> nearestStrike(
        String underlying, LocalDate expiry, String optionType, BigDecimal spot);
  }

  private final Catalog catalog;

  /** Wires the registry catalog (the JDBC impl in prod, a fake in tests). */
  public OptionContractSelector(Catalog catalog) {
    this.catalog = catalog;
  }

  /**
   * Resolves the option to trade for one entry. Empty when the bias side isn't in {@code optionTypes}
   * (e.g. a CE-only strategy on a short signal), no expiry matches the rule, or no strike is listed.
   *
   * @param underlying the index symbol (e.g. {@code NIFTY})
   * @param spot the underlying price at the entry bar
   * @param onOrAfter the entry date — the expiry must be on or after it
   * @param mode the {@code expiry} rule
   * @param expiryOffset the Nth-out index for {@link ExpiryMode#OFFSET} (0 = nearest), else ignored
   * @param longBias true for a long signal (→ CE), false for a short signal (→ PE)
   * @param optionTypes the schema's allowed sides ({@code CE}/{@code PE}); empty = both allowed
   */
  public Optional<OptionContract> select(
      String underlying,
      BigDecimal spot,
      LocalDate onOrAfter,
      ExpiryMode mode,
      int expiryOffset,
      boolean longBias,
      Set<String> optionTypes) {
    String optionType = longBias ? "CE" : "PE";
    if (!optionTypes.isEmpty() && !optionTypes.contains(optionType)) {
      return Optional.empty();
    }
    LocalDate expiry = pickExpiry(catalog.expiriesOnOrAfter(underlying, onOrAfter), mode, expiryOffset);
    if (expiry == null) {
      return Optional.empty();
    }
    return catalog.nearestStrike(underlying, expiry, optionType, spot);
  }

  /**
   * Picks the expiry from the ascending candidate list. {@code NEAREST_WEEKLY} = the nearest expiry;
   * {@code NEAREST_MONTHLY} = the nearest non-weekly; {@code OFFSET} = the {@code offset}-th candidate
   * (0-based). Null when no candidate satisfies the rule.
   */
  static LocalDate pickExpiry(List<Expiry> ascending, ExpiryMode mode, int offset) {
    if (ascending == null || ascending.isEmpty()) {
      return null;
    }
    return switch (mode) {
      case NEAREST_WEEKLY -> ascending.get(0).date();
      case NEAREST_MONTHLY ->
          ascending.stream().filter(e -> !e.weekly()).map(Expiry::date).findFirst().orElse(null);
      case OFFSET -> offset >= 0 && offset < ascending.size() ? ascending.get(offset).date() : null;
    };
  }
}
