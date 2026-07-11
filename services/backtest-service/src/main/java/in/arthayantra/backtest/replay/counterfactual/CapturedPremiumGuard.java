package in.arthayantra.backtest.replay.counterfactual;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The captured-data guard for the counterfactual replay (EVO E3 item 9 / §3.3.4: "captured (real) data
 * only — never derived history"). It reads {@code marketdata.candles.source} directly (D10 read, the
 * CD-1 grant model) and refuses any leg/window whose premium would NOT come from real captured candles.
 *
 * <p><b>Why a source check, not a date cutoff.</b> Historical OI/chain before the ~2026-06-15
 * live-capture start is VIRTUAL (read-time derived by market-data's {@code CandleDerivedChainReader},
 * flagged {@code oiDerived}) — but that derivation produces StrikePoint rows, never candle rows. The
 * option PREMIUM path this replay reads is always real: {@code candles.source} is a CLOSED enum of
 * ingestion labels (V018/V020 — KITE / TICK_AGG / BACKFILL / OPENALGO / EXPIRYTRACK / OPENCHART /
 * BHAVCOPY), with the ONLY synthetic value being {@code MOCK} (the mock-profile feed). So the honest
 * guard is provenance, not a date: a window has captured real premium iff real candle rows exist for the
 * leg. This is strictly the spec's "practical floor" — a pre-2026-06-15 window passes ONLY when it
 * carries real (backfilled) provenance (exactly the runbook §4.B case), and fails when it is empty
 * (nothing captured) or MOCK (synthetic). {@link #REAL_SOURCES} is every candle source but {@code MOCK}.
 */
@Component
public class CapturedPremiumGuard {

  /** Captured/real candle sources — the V018/V020 enum minus the mock-profile {@code MOCK}. */
  static final Set<String> REAL_SOURCES =
      Set.of("KITE", "TICK_AGG", "BACKFILL", "OPENALGO", "EXPIRYTRACK", "OPENCHART", "BHAVCOPY");

  private final JdbcTemplate jdbc;

  /** Wires JDBC (connected as the owner; marketdata read via the CD-1 grant model). */
  public CapturedPremiumGuard(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Refuses ({@code 422}, clear reason) unless {@code [from, to)} of the option leg is served by real
   * captured 1m candles: empty ⇒ {@code DATA_GAP} (nothing captured — cannot price from real data);
   * any non-real ({@code MOCK}/unknown) source ⇒ {@code DATA_STALE} (derived/synthetic — never
   * counterfactual-eligible). Passing means every present source is in {@link #REAL_SOURCES}.
   */
  public void assertCaptured(
      String exchange, String tradingsymbol, OffsetDateTime from, OffsetDateTime to) {
    List<String> sources =
        jdbc.query(
            "SELECT DISTINCT source FROM marketdata.candles "
                + "WHERE exchange=? AND tradingsymbol=? AND \"interval\"='1m' "
                + "AND bucket >= ? AND bucket < ?",
            (rs, n) -> rs.getString("source"),
            exchange,
            tradingsymbol,
            from,
            to);
    if (sources.isEmpty()) {
      throw new ApiException(
          422,
          ErrorCodes.DATA_GAP,
          "no captured premium candles for "
              + exchange
              + ":"
              + tradingsymbol
              + " over ["
              + from
              + ", "
              + to
              + ") — counterfactual replay needs real captured option candles (live-capture regime from"
              + " ~2026-06-15); nothing is stored for this leg/window",
          Map.of("exchange", exchange, "tradingsymbol", tradingsymbol,
              "from", from.toString(), "to", to.toString()));
    }
    for (String source : sources) {
      if (!REAL_SOURCES.contains(source)) {
        throw new ApiException(
            422,
            ErrorCodes.DATA_STALE,
            exchange
                + ":"
                + tradingsymbol
                + " premium over ["
                + from
                + ", "
                + to
                + ") is not from captured real data (source="
                + source
                + ") — counterfactual replay refuses derived/synthetic premium (captured real data only,"
                + " never derived history)",
            Map.of("exchange", exchange, "tradingsymbol", tradingsymbol, "source", source));
      }
    }
  }

  /**
   * The lot size for an option leg by its candle key, resolved from the contract registry the service
   * already reads — {@code marketdata.expired_contracts} first (the premium-replay registry), then live
   * {@code marketdata.instruments} for a not-yet-expired contract. Empty when neither carries it; the
   * caller then refuses ({@code 422}) rather than costing a trade at an assumed lot size.
   */
  public Optional<Integer> lotSize(String exchange, String tradingsymbol) {
    Optional<Integer> expired =
        jdbc
            .query(
                "SELECT lot_size FROM marketdata.expired_contracts WHERE exchange=? AND tradingsymbol=?",
                (rs, n) -> rs.getInt("lot_size"),
                exchange,
                tradingsymbol)
            .stream()
            .findFirst();
    if (expired.isPresent()) {
      return expired;
    }
    return jdbc
        .query(
            "SELECT lot_size FROM marketdata.instruments "
                + "WHERE exchange=? AND tradingsymbol=? AND lot_size IS NOT NULL",
            (rs, n) -> rs.getInt("lot_size"),
            exchange,
            tradingsymbol)
        .stream()
        .findFirst();
  }
}
