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
    boolean active,
    @Schema(
            description =
                "True when the row carries no instrument-master metadata at all — the master sync"
                    + " has never populated it, so token/name/segment are unknown rather than"
                    + " merely absent. Purely derived from the three fields above it; a consumer"
                    + " can recompute it from this same response.")
        boolean masterMetadataMissing) {

  /**
   * ⚠️ Compact canonical (ledger H30): {@code masterMetadataMissing} is DERIVED here and whatever
   * was passed for it is DISCARDED. That is the point — the rule lives on the one path every
   * construction must go through, including any future Jackson deserialization, so no caller can
   * assert a flag that disagrees with the row it describes. A 13-argument delegate below keeps
   * existing producers source-compatible; it passes a placeholder this constructor overwrites.
   *
   * <p>Why the flag is needed at all: {@code GET /api/v1/instruments/{exchange}/{tradingsymbol}}
   * answers 200 for a row that exists, and 404 only for one that does not. It has no way to say
   * "we hold a key for this and know nothing else about it" — which is the truth for 182,491 of
   * the master's 303,422 rows (computed 2026-08-25: 175,766 NFO and 6,423 BFO option contracts,
   * every one past expiry, plus 302 NSE equities). They are written by {@code tools/historical-import}
   * ({@code ingest.py} {@code _UPSERT_INSTRUMENT}), which inserts a bare key so its candles have
   * an instrument to hang off. They are deliberate placeholders, not corruption.
   *
   * <p>⚠️ {@code active} does NOT already answer this and must not be read as if it did: 57,569
   * inactive rows carry full master metadata (delisted equities, expired contracts the sync once
   * knew). {@code active == false} conflates "it stopped trading" with "we never knew it"; this
   * flag separates them. Conversely every one of the 182,491 hollow rows is inactive, so the flag
   * is always {@code false} on the {@code is_active}-filtered list/search endpoints and is
   * informative only on the by-key read.
   *
   * <p>{@code exchange_token} is hollow on the same rows but is deliberately NOT part of the rule:
   * it is not a component of this record, so keying on it would make the flag unverifiable from
   * the response. It is also not needed — the three-field rule matches exactly 182,491 rows, the
   * same set (computed 2026-08-25). The rule excludes the six {@code SYN-CONT} synthetic
   * continuous-future rows, which are tokenless by design but do carry a name and segment.
   */
  public Instrument {
    masterMetadataMissing = instrumentToken == null && name == null && segment == null;
  }

  /** The producer form: every caller in the codebase uses this and never states the flag. */
  public Instrument(
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
      boolean active) {
    // The trailing `false` is a placeholder, NOT a claim: the compact canonical above overwrites
    // it unconditionally. Passing `true` here would change nothing.
    this(
        exchange,
        tradingsymbol,
        instrumentToken,
        name,
        segment,
        instrumentType,
        underlyingExchange,
        underlyingTradingsymbol,
        expiry,
        strike,
        tickSize,
        lotSize,
        active,
        false);
  }
}
