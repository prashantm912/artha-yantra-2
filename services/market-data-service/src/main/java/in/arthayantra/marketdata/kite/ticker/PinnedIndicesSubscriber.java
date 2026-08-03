package in.arthayantra.marketdata.kite.ticker;

import in.arthayantra.marketdata.kite.InstrumentKey;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Pins the always-on index subscriptions (B-6 eviction top priority): NIFTY 50, NIFTY BANK and —
 * per FP-14 — INDIA VIX, an ordinary NSE index instrument with no special casing. Runs after the
 * bootstrap sync (ordered last) so the master can resolve tokens; unknown instruments are skipped
 * with a warning, never a startup failure.
 */
@Component
public class PinnedIndicesSubscriber {

  private static final Logger log = LoggerFactory.getLogger(PinnedIndicesSubscriber.class);

  /** The cash exchanges — the only ones on which a pin could be an ordinary tradable EQUITY. */
  private static final java.util.Set<String> CASH_EXCHANGES = java.util.Set.of("NSE", "BSE");

  /**
   * Every cash-exchange instrument this pin list is allowed to put on the live tick feed. All seven are
   * INDICES; none is tradable, so none can be a paper holding. See {@link #assertNoCashEquityPinned}.
   */
  private static final java.util.Set<String> ALLOWED_CASH_PINS =
      java.util.Set.of(
          "NIFTY 50",
          "NIFTY BANK",
          "NIFTY FIN SERVICE",
          "NIFTY MID SELECT",
          "INDIA VIX",
          "SENSEX",
          "BANKEX");

  private final SubscriptionRegistry registry;
  private final List<String> pinned;

  /** Wires the configured pin list ({@code EXCHANGE:TRADINGSYMBOL} comma-separated). */
  public PinnedIndicesSubscriber(
      SubscriptionRegistry registry,
      @Value("${artha.subscriptions.pinned-indices:NSE:NIFTY 50,NSE:NIFTY BANK,NSE:INDIA VIX}")
          List<String> pinned) {
    this.registry = registry;
    this.pinned = pinned;
    assertNoCashEquityPinned(pinned);
  }

  /**
   * RATCHET (PR #1251): this list is the only place a cash-exchange instrument is declared onto the WS
   * ticker, and therefore the only static declaration that can put an EQUITY into the {@code ticks:last}
   * hash. {@code artha.futures.underlyings} and {@code artha.options.atm-pinner.underlyings} name
   * underlyings that resolve to NFO/BFO contracts; {@code artha.futures.oi-snapshot-underlyings} and
   * {@code artha.futures.bank-stocks} do name equities but drive REST snapshots and a UI grid, never a
   * WS subscription.
   *
   * <p>Why a money-path guard lives in a subscription bean: {@code PaperBracketEvaluator} prices every
   * open paper position off {@code ticks:last} and compares it to the STORED {@code
   * paper_positions.stop_loss} — a level written once at entry, on the corporate-action plane current
   * then, which nothing re-scales when a split retroactively re-planes the market. #1251 closed that on
   * the daily batch path AND made this poller skip EOD-managed swing books outright, so putting an
   * equity here no longer arms that exposure — but it does invalidate the assumption the whole doctrine
   * rests on ("no NSE equity is subscribed to the live tick feed",
   * docs/signal-analysis/2026-08-02-manas-exit-stop-doctrine.md §1/§3), and it is exactly the change
   * that doctrine calls a feature request rather than a flip. The collision belongs here, at authoring
   * time, not in a live-Redis observation nobody runs.
   *
   * <p>Guarding at CONSTRUCTION rather than in a test that reads the YAML follows {@code
   * VcpMinBaseWeeksTripwireTest}'s precedent: it fires for the compiled-in default, for an {@code
   * application.yml} override and for any future {@code .env} passthrough alike, in every context that
   * boots this bean — not only where someone remembered to assert.
   *
   * <p><b>Adding another INDEX is legitimate</b> — extend {@link #ALLOWED_CASH_PINS} after confirming
   * {@code PaperBracketEvaluator}'s skip still covers every book holding that instrument's segment.
   * Adding an EQUITY is the thing this exists to stop. Not covered: subscriptions the live engine takes
   * dynamically from published strategy configs, which are not statically declared anywhere.
   */
  private static void assertNoCashEquityPinned(List<String> entries) {
    for (String entry : entries) {
      int colon = entry == null ? -1 : entry.indexOf(':');
      if (colon <= 0) {
        continue; // malformed — ensurePinned() already warns and skips it
      }
      String exchange = entry.substring(0, colon).trim().toUpperCase(java.util.Locale.ROOT);
      String symbol = entry.substring(colon + 1).trim();
      if (CASH_EXCHANGES.contains(exchange) && !ALLOWED_CASH_PINS.contains(symbol)) {
        throw new IllegalStateException(
            "artha.subscriptions.pinned-indices declares '"
                + entry
                + "' — a cash-exchange instrument that is not one of the seven allowed INDICES "
                + ALLOWED_CASH_PINS
                + ". If this is an EQUITY you are putting cash equities on the live tick feed, which"
                + " breaks the assumption the swing exit doctrine rests on"
                + " (docs/signal-analysis/2026-08-02-manas-exit-stop-doctrine.md §1/§3: 'no NSE equity"
                + " is subscribed to the live tick feed') and which PR #1251 hardened"
                + " PaperBracketEvaluator against — re-read that PR and confirm the EOD-managed-book"
                + " skip still covers every book that could hold this symbol before proceeding. If it"
                + " is another INDEX, add it to PinnedIndicesSubscriber.ALLOWED_CASH_PINS.");
      }
    }
  }

  /** Subscribes the pinned set; ordered after the bootstrap sync's ready-listener. */
  @EventListener(ApplicationReadyEvent.class)
  @Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
  public void pinIndices() {
    ensurePinned();
  }

  /** Re-resolves pins after every instrument sync — startup-unknown indices land here. */
  @EventListener(in.arthayantra.marketdata.kite.InstrumentMasterUpdated.class)
  public void onMasterUpdated() {
    ensurePinned();
  }

  /** Idempotent pin pass — also callable after instrument syncs. */
  public void ensurePinned() {
    for (String entry : pinned) {
      int colon = entry.indexOf(':');
      if (colon <= 0) {
        log.warn("malformed pinned index entry '{}'", entry);
        continue;
      }
      InstrumentKey key =
          new InstrumentKey(entry.substring(0, colon).trim(), entry.substring(colon + 1).trim());
      try {
        registry.subscribe(
            "system-pinned", key, SubscriptionMode.QUOTE, SubscriptionPriority.PINNED_INDEX);
      } catch (Exception unknownYet) {
        log.warn("pinned index {} not resolvable yet: {}", key.canonical(), unknownYet.getMessage());
      }
    }
  }
}
