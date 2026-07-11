package in.arthayantra.backtest.replay.counterfactual;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code POST /api/v1/backtests/counterfactual} body (EVO E3 item 9, §3.3.4) — the live-signal-analysis
 * runbook's §4.B "counterfactual band grid" as a job. It replays REAL observed entries' CAPTURED option
 * premium under alternative exit-knob sets, for exit-band candidates the shadow book cannot express.
 *
 * <p><b>Self-contained by design.</b> The entries ride IN the request (not read from the strategy schema)
 * so the job needs no cross-schema grant and pins its own inputs. Each {@link Entry} names the observed
 * option leg by its candle key {@code (exchange, tradingsymbol)} plus the real {@code entryTime} /
 * {@code entryPremium} / {@code qty}; the premium PATH is still read from the stored captured candles
 * (the whole point — real premium, never fabricated). {@code [from, to)} bounds which entries are
 * accepted (a sanity window); each entry's premium is replayed from its {@code entryTime} to that day's
 * session close.
 */
public record CounterfactualRunRequest(
    String from, String to, List<Entry> entries, List<Variant> variants) {

  /**
   * One REAL observed entry to replay. {@code signalId} is an optional back-reference to the firing/
   * rejection row it came from (carried through for provenance, never dereferenced). The leg is the
   * option's own candle key; {@code entryPremium} is the observed fill (the exit levels anchor on it,
   * not on the candle's first bar); {@code qty} is the traded quantity (a lot multiple).
   */
  public record Entry(
      String signalId,
      String exchange,
      String tradingsymbol,
      String entryTime,
      BigDecimal entryPremium,
      long qty) {}

  /** A named exit-knob candidate scored on every entry (e.g. {@code "tp35-sl20"}, {@code "tp50"}). */
  public record Variant(String name, ExitKnobs exitKnobs) {}
}
