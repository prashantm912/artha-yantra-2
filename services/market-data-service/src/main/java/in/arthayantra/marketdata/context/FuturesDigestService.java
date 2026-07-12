package in.arthayantra.marketdata.context;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.futures.FuturesTermStructureService;
import in.arthayantra.marketdata.options.OiInterpretation;
import in.arthayantra.marketdata.options.OiInterval;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Futures decision-support digest (intelligence-layer design 2026-07-10 §6.1.2) — a fold over the
 * EXISTING captured futures OI snapshots ({@code futures_oi_snapshots}) plus the live term-structure
 * read, no new raw data (§13 row 4). Per captured underlying (the 6 F&O indices + 17 NSE bank
 * stocks) it takes the FRONT (nearest-expiry) contract's newest snapshot bucket, diffs it against
 * the prior bucket for the price%/ΔOI quadrant (the shared {@link OiInterpretation} classifier the
 * {@code /banks-grid} fold uses), aggregates the 17-bank subset into a summary, and attaches the
 * live term-structure (basis vs spot + contango/backwardation) for the primary index.
 *
 * <p><b>Leaf-module reuse.</b> This service lives in the {@code context} module and must not reach
 * into another module's internal {@code .analytics} sub-package (the market-data cross-module
 * convention: depend on base-package types only). It therefore reads {@code futures_oi_snapshots}
 * with its own JDBC fold (the same {@code public.last(col, ts)} over IST {@code time_bucket}
 * end-of-window convention as {@code FuturesSnapshotReader}) rather than injecting that internal
 * reader, and reuses only {@link FuturesTermStructureService} (a futures base-package type).
 *
 * <p><b>History-honesty (§6.5) / never-5xx.</b> The digest is a typed record that carries {@code
 * dataTrust} (OK/DEGRADED/BLOCKED) + reasons; it never 5xxes on missing data. A live read whose
 * newest bucket is a prior session is stale-anchored and degraded (#745's stale-anchor rule); a
 * single bucket (no OI diff), an off-hours/absent term structure, or an uncovered calendar year each
 * degrade with a named reason. No snapshot at all AND no term structure ⇒ a {@code BLOCKED} shell.
 */
@Service
public class FuturesDigestService {

  /** One captured underlying's front-contract price%/ΔOI quadrant (the {@code /banks-grid} shape). */
  public record UnderlyingQuadrant(
      String underlying,
      String tradingsymbol,
      LocalDate expiry,
      BigDecimal ltp,
      BigDecimal pricePct,
      long oi,
      long oiChange,
      BigDecimal oiPct,
      String interpretation) {}

  /** The 17-bank subset rolled into interpretation counts + net ΔOI (the "banks summary" fold). */
  public record BanksSummary(
      int total,
      int longBuildup,
      int shortBuildup,
      int shortCovering,
      int longUnwinding,
      long netOiChange) {}

  /** The primary-index live term-structure headline (basis vs spot + contango/backwardation). */
  public record TermStructureState(
      String underlying,
      BigDecimal spot,
      String state,
      BigDecimal calendarSpread,
      BigDecimal nearBasis,
      boolean stale,
      OffsetDateTime asOf) {}

  /** The futures context digest. {@code dataTrust} = OK/DEGRADED/BLOCKED. */
  public record FuturesDigest(
      List<UnderlyingQuadrant> underlyings,
      BanksSummary banks,
      TermStructureState termStructure,
      OffsetDateTime asOf,
      String dataTrust,
      List<String> trustReasons) {

    static FuturesDigest blocked(String reason) {
      return new FuturesDigest(List.of(), null, null, null, "BLOCKED", List.of(reason));
    }
  }

  private static final Logger log = LoggerFactory.getLogger(FuturesDigestService.class);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final JdbcTemplate jdbc;
  private final FuturesTermStructureService termStructureService;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final List<String> capturedUnderlyings;
  private final Set<String> bankStocks;
  private final String termIndex;
  private final OiInterval interval;

  /** Wires the JDBC fold, the reused term-structure read, and the captured-universe knobs. */
  public FuturesDigestService(
      JdbcTemplate jdbc,
      FuturesTermStructureService termStructureService,
      MarketCalendar calendar,
      Clock clock,
      @Value("${artha.futures.oi-snapshot-underlyings:NIFTY 50,NIFTY BANK}") String capturedCsv,
      @Value("${artha.futures.bank-stocks:}") String bankStocksCsv,
      @Value("${artha.context.futures-term-index:NIFTY 50}") String termIndex,
      @Value("${artha.context.futures-interval:15m}") String futuresInterval) {
    this.jdbc = jdbc;
    this.termStructureService = termStructureService;
    this.calendar = calendar;
    this.clock = clock;
    this.capturedUnderlyings = csv(capturedCsv);
    this.bankStocks = new LinkedHashSet<>(csv(bankStocksCsv));
    this.termIndex = termIndex;
    this.interval = OiInterval.parse(futuresInterval);
  }

  private static List<String> csv(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
  }

  /**
   * The futures digest. {@code date} null = live (newest overall bucket); a past IST date reads that
   * session's newest bucket (history). The per-underlying quadrant and the banks summary come from
   * the snapshot fold; the term-structure block is a live quote read (fail-soft).
   */
  public FuturesDigest digest(LocalDate date) {
    List<String> reasons = new ArrayList<>();
    List<UnderlyingQuadrant> quadrants = new ArrayList<>();
    OffsetDateTime newest = null;

    if (capturedUnderlyings.isEmpty()) {
      reasons.add("no captured futures underlyings configured");
    } else {
      List<OffsetDateTime> buckets = latestTwoBuckets(date);
      if (buckets.isEmpty()) {
        reasons.add("no futures snapshot captured");
      } else {
        newest = buckets.get(0);
        OffsetDateTime prior = buckets.size() > 1 ? buckets.get(buckets.size() - 1) : null;
        quadrants = quadrants(newest, prior);
        if (prior == null) {
          reasons.add("only one futures snapshot bucket - no OI diff");
        }
        LocalDate sessionDay = newest.atZoneSameInstant(Ist.ZONE).toLocalDate();
        if (date == null) {
          LocalDate todayIst = LocalDate.ofInstant(clock.instant(), Ist.ZONE);
          if (!sessionDay.equals(todayIst)) {
            reasons.add("stale snapshot - newest bucket is " + sessionDay + ", not today");
          }
        }
      }
    }

    BanksSummary banks = quadrants.isEmpty() ? null : banksSummary(quadrants);
    TermStructureState term = termStructure(reasons);
    if (date != null) {
      reasons.add("historical read");
    }

    if (quadrants.isEmpty() && term == null) {
      return FuturesDigest.blocked(
          reasons.isEmpty() ? "no futures data" : String.join("; ", reasons));
    }
    String trust = reasons.isEmpty() ? "OK" : "DEGRADED";
    OffsetDateTime asOf = newest != null ? newest : (term != null ? term.asOf() : null);
    return new FuturesDigest(quadrants, banks, term, asOf, trust, List.copyOf(reasons));
  }

  /** The newest-two distinct IST buckets across the captured universe (end-of-window convention). */
  private List<OffsetDateTime> latestTwoBuckets(LocalDate date) {
    String placeholders = String.join(",", java.util.Collections.nCopies(capturedUnderlyings.size(), "?"));
    StringBuilder sql =
        new StringBuilder(
            "SELECT DISTINCT public.time_bucket(INTERVAL '"
                + interval.pgInterval()
                + "', ts - INTERVAL '1 second', 'Asia/Kolkata') AS b "
                + "FROM futures_oi_snapshots WHERE underlying IN ("
                + placeholders
                + ")");
    List<Object> args = new ArrayList<>(capturedUnderlyings);
    if (date != null) {
      sql.append(" AND (ts AT TIME ZONE 'Asia/Kolkata')::date = ?");
      args.add(java.sql.Date.valueOf(date));
    }
    sql.append(" ORDER BY b DESC LIMIT 2");
    return jdbc.query(sql.toString(), (rs, n) -> rs.getObject("b", OffsetDateTime.class), args.toArray());
  }

  /** One row per contract at the two anchoring buckets, de-duped per contract via {@code last()}. */
  private record FutRow(
      OffsetDateTime bucket,
      String underlying,
      String tradingsymbol,
      BigDecimal ltp,
      Long oi,
      BigDecimal prevClose,
      LocalDate expiry) {}

  /** Fold the front (nearest-expiry) contract per underlying at {@code newest}, diffed vs {@code prior}. */
  private List<UnderlyingQuadrant> quadrants(OffsetDateTime newest, OffsetDateTime prior) {
    OffsetDateTime from = prior != null ? prior : newest;
    List<FutRow> rows = seriesAll(from, newest.plus(interval.bucket()));
    Map<String, FutRow> oldBySym = new HashMap<>();
    Map<String, FutRow> frontByUnderlying = new HashMap<>();
    for (FutRow r : rows) {
      if (prior != null && r.bucket().equals(prior)) {
        oldBySym.put(r.tradingsymbol(), r);
      }
      if (r.bucket().equals(newest) && r.expiry() != null && r.underlying() != null) {
        frontByUnderlying.merge(
            r.underlying(), r, (a, b) -> b.expiry().isBefore(a.expiry()) ? b : a);
      }
    }
    List<UnderlyingQuadrant> out = new ArrayList<>();
    for (FutRow cur : frontByUnderlying.values()) {
      FutRow old = oldBySym.get(cur.tradingsymbol());
      long curOi = cur.oi() == null ? 0 : cur.oi();
      long oiChange = 0;
      BigDecimal oiPct = null;
      String interp = null;
      if (old != null) {
        long priorOi = old.oi() == null ? 0 : old.oi();
        oiChange = curOi - priorOi;
        BigDecimal ltpDelta =
            (cur.ltp() == null ? BigDecimal.ZERO : cur.ltp())
                .subtract(old.ltp() == null ? BigDecimal.ZERO : old.ltp());
        interp = OiInterpretation.classify(ltpDelta, oiChange).name();
        oiPct =
            priorOi == 0
                ? null
                : BigDecimal.valueOf(oiChange)
                    .multiply(HUNDRED)
                    .divide(BigDecimal.valueOf(priorOi), 2, RoundingMode.HALF_UP);
      }
      out.add(
          new UnderlyingQuadrant(
              cur.underlying(),
              cur.tradingsymbol(),
              cur.expiry(),
              cur.ltp(),
              pricePct(cur, old),
              curOi,
              oiChange,
              oiPct,
              interp));
    }
    out.sort(Comparator.comparingLong(UnderlyingQuadrant::oi).reversed());
    return out;
  }

  private List<FutRow> seriesAll(OffsetDateTime from, OffsetDateTime to) {
    String placeholders = String.join(",", java.util.Collections.nCopies(capturedUnderlyings.size(), "?"));
    String sql =
        "SELECT public.time_bucket(INTERVAL '"
            + interval.pgInterval()
            + "', ts - INTERVAL '1 second', 'Asia/Kolkata') AS b, underlying, tradingsymbol, "
            + " public.last(ltp, ts) AS ltp, public.last(oi, ts) AS oi, "
            + " public.last(prev_close, ts) AS prev_close, public.last(expiry, ts) AS expiry "
            + "FROM futures_oi_snapshots WHERE underlying IN ("
            + placeholders
            + ") AND ts >= ? AND ts < ? "
            + "GROUP BY b, underlying, tradingsymbol";
    List<Object> args = new ArrayList<>(capturedUnderlyings);
    args.add(Timestamp.from(from.plusSeconds(1).toInstant()));
    args.add(Timestamp.from(to.plusSeconds(1).toInstant()));
    return jdbc.query(
        sql,
        (rs, n) ->
            new FutRow(
                rs.getObject("b", OffsetDateTime.class),
                rs.getString("underlying"),
                rs.getString("tradingsymbol"),
                rs.getBigDecimal("ltp"),
                rs.getObject("oi", Long.class),
                rs.getBigDecimal("prev_close"),
                rs.getObject("expiry", LocalDate.class)),
        args.toArray());
  }

  /** Day price% off the captured {@code prevClose}; falls back to the prior bucket LTP. */
  private static BigDecimal pricePct(FutRow cur, FutRow old) {
    BigDecimal base =
        cur.prevClose() != null && cur.prevClose().signum() != 0
            ? cur.prevClose()
            : (old != null && old.ltp() != null && old.ltp().signum() != 0 ? old.ltp() : null);
    if (base == null || cur.ltp() == null) {
      return null;
    }
    return cur.ltp().subtract(base).multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
  }

  private BanksSummary banksSummary(List<UnderlyingQuadrant> quadrants) {
    int lb = 0;
    int sb = 0;
    int sc = 0;
    int lu = 0;
    int total = 0;
    long net = 0;
    for (UnderlyingQuadrant q : quadrants) {
      if (!bankStocks.contains(q.underlying())) {
        continue;
      }
      total++;
      net += q.oiChange();
      if (q.interpretation() == null) {
        continue;
      }
      switch (q.interpretation()) {
        case "LONG_BUILDUP" -> lb++;
        case "SHORT_BUILDUP" -> sb++;
        case "SHORT_COVERING" -> sc++;
        case "LONG_UNWINDING" -> lu++;
        default -> {
          /* unreachable */
        }
      }
    }
    return new BanksSummary(total, lb, sb, sc, lu, net);
  }

  /** The live term-structure for the primary index; fail-soft to null + a reason (never a 5xx). */
  private TermStructureState termStructure(List<String> reasons) {
    try {
      FuturesTermStructureService.TermStructure ts = termStructureService.termStructure(termIndex);
      BigDecimal nearBasis =
          ts.contracts().isEmpty() ? null : ts.contracts().get(0).basisAbsolute();
      if (ts.stale()) {
        reasons.add("term structure stale (off-hours / last-good)");
      }
      return new TermStructureState(
          ts.underlying(), ts.spot(), ts.state(), ts.calendarSpread(), nearBasis, ts.stale(), ts.asOf());
    } catch (RuntimeException e) {
      log.debug("futures-digest term structure unavailable for {}: {}", termIndex, e.getMessage());
      reasons.add("term structure unavailable for " + termIndex);
      return null;
    }
  }
}
