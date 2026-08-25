package in.arthayantra.marketdata.nse;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The OFFICIAL NSE end-of-day close for a set of symbols on one settled session (ledger H9).
 *
 * <p>It exists because {@code /api/v1/market/candles}@1d CANNOT answer this question. Kite's daily
 * bar covers the CONTINUOUS session only and stops at 15:15, so it misses the 15:15–15:30 closing
 * auction that sets NSE's official close — measured 2026-08-13, 0 of 22 KITE 1d bars over the swing
 * book matched {@code nse_eod_bhavcopy.close_price}. Nor can the candles path be FIXED into
 * answering it: {@code GapDetector}'s B-4 recency test compares the bucket END, and today's 1d
 * bucket ends at 00:00 IST tomorrow. The bucket therefore counts as missing while
 * {@code bucketEnd > now - 10m} — i.e. THROUGH 23:50 IST and on until roughly <b>00:10 IST the
 * NEXT day</b>, when the 10-minute recency window finally clears its close. Every evening read of
 * today's 1d bar inside that span re-fetches from Kite and stores it through
 * {@code upsertAuthoritativeAll}, which REPLACES whatever the 18:45 bhavcopy ingest wrote. A
 * separate read of the bhavcopy plane is the only stable seam.
 *
 * <p>Deliberately a typed record and not a {@code Map<String, Object>}: springdoc cannot enumerate a
 * Map, so the contract gate and the generated TS types would be blind to it, and
 * {@code MapReturnRatchetTest} freezes market-data-service at 2 Map-returning handlers — a third
 * fails the strategy-gateway CI shard.
 *
 * <p><b>Additive only.</b> Nothing existing reads this; the swing settle is its first consumer.
 */
@RestController
@RequestMapping("/api/v1/market/eod-close")
public class OfficialCloseController {

  /** Response envelope (COMMON §3 {@code items} shape). Absent symbols are simply not present. */
  public record OfficialCloseResponse(List<OfficialClose> items) {}

  private final NseEodBhavcopyRepository bhavcopy;

  /** Wires the bhavcopy read. */
  public OfficialCloseController(NseEodBhavcopyRepository bhavcopy) {
    this.bhavcopy = bhavcopy;
  }

  /**
   * Official closes for {@code symbols} on {@code date}. A symbol with no bhavcopy row for that
   * date, or only NULL-priced rows, is OMITTED — never returned with a null price, so the caller can
   * tell "the exchange has not published this" apart from "published as nothing".
   *
   * <p>{@code date} is a plain {@code LocalDate} matched against the {@code trade_date} COLUMN. That
   * dissolves the {@code bucket::date} join trap rather than surviving it: a 1d candle bucket sits at
   * IST midnight = 18:30 UTC of the PREVIOUS day, so comparing {@code bucket::date} to a date column
   * silently compares CONSECUTIVE TRADING DAYS — a mistake that once reported 1.66% mean divergence
   * on a plane that was 2639/2639 EXACT once aligned. There is no bucket, and no join, anywhere on
   * this path.
   *
   * <p>Only NSE has a bhavcopy plane. Any other exchange answers 200 with an EMPTY list rather than
   * 400: the sole caller is an exit settle that must never be refused, and an empty list routes it to
   * its counted, alerted fallback instead of throwing on a money path.
   */
  @GetMapping
  public ResponseEntity<OfficialCloseResponse> eodClose(
      @RequestParam String exchange,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam List<String> symbols) {
    if (!"NSE".equalsIgnoreCase(exchange)) {
      return ResponseEntity.ok(new OfficialCloseResponse(List.of()));
    }
    return ResponseEntity.ok(new OfficialCloseResponse(bhavcopy.officialClosesOn(date, symbols)));
  }
}
