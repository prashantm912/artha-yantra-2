package in.arthayantra.marketdata.screener.minervini;

import in.arthayantra.marketdata.screener.minervini.geometry.VcpFootprint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Minervini SEPA screener surface (Track-1). Returns a TYPED record (not {@code Map}) so it
 * enumerates into the springdoc contract and does not bump the market-data Map-return ratchet.
 * {@code GET} serves the persisted daily screen (fast path, the watchlist consumer);
 * {@code POST /run} recomputes on demand. Decimals cross the wire as JSON strings (global Jackson
 * config; the React {@code core/decimal} helper parses them). Path is under {@code /api/v1/market/**}
 * so the edge-gateway allowlist already covers it.
 */
@RestController
@RequestMapping("/api/v1/market/screener/minervini")
public class MinerviniController {

  /** One screener row (the 8 gates + RS-rank + Stage + low-cap inputs). */
  public record Row(
      String symbol,
      String exchange,
      BigDecimal close,
      BigDecimal sma50,
      BigDecimal sma150,
      BigDecimal sma200,
      BigDecimal high52w,
      BigDecimal low52w,
      BigDecimal pctFromHigh,
      BigDecimal pctAboveLow,
      BigDecimal rsRank,
      BigDecimal avgTurnover50,
      BigDecimal freeFloatMcapCr,
      BigDecimal freeFloatPct,
      boolean[] gates,
      int gatesPassed,
      boolean passesAll,
      Integer stage) {}

  /** The {items} envelope + as-of + coverage. */
  public record ScreenResponse(
      List<Row> items, LocalDate screenDate, int coverage, int limit, int offset) {}

  /** VCP / base geometry for one candidate (MV-5.5). Null-valued when the base is not a VCP. */
  public record Geometry(
      boolean isVcp,
      String footprint,
      BigDecimal pivot,
      BigDecimal deepestPct,
      BigDecimal tightestPct,
      Integer contractionCount,
      Integer baseWeeks,
      Integer baseDurationDays,
      boolean volumeDryUp,
      boolean shakeout,
      Integer baseCount,
      BigDecimal cheatPivot,
      boolean thrust,
      String rejectReason) {}

  /**
   * The full analyzer payload for one candidate (MV-5.5): the 8 Trend-Template gates + Stage + the
   * screen numerics (from the persisted screen row — {@code scanned=false} when the symbol was not
   * in the screen), the low-cap fundamentals, and the live VCP geometry.
   */
  public record CandidateAnalysis(
      String symbol,
      String exchange,
      LocalDate screenDate,
      boolean scanned,
      BigDecimal close,
      BigDecimal sma50,
      BigDecimal sma150,
      BigDecimal sma200,
      BigDecimal high52w,
      BigDecimal low52w,
      BigDecimal pctFromHigh,
      BigDecimal pctAboveLow,
      BigDecimal rsRank,
      BigDecimal avgTurnover50,
      BigDecimal freeFloatMcapCr,
      BigDecimal freeFloatPct,
      boolean[] gates,
      int gatesPassed,
      boolean passesAll,
      Integer stage,
      Geometry geometry) {}

  private final TrendTemplateService screener;
  private final MinerviniScreenRepository repo;
  private final MinerviniGeometryService geometryService;
  private final MinerviniSetupsRepository setupsRepo;
  private final MinerviniFunnelService funnelService;
  private final MinerviniHitRateService hitRateService;

  /** Wires the screener + screen/geometry repositories + the funnel + hit-rate services. */
  public MinerviniController(
      TrendTemplateService screener,
      MinerviniScreenRepository repo,
      MinerviniGeometryService geometryService,
      MinerviniSetupsRepository setupsRepo,
      MinerviniFunnelService funnelService,
      MinerviniHitRateService hitRateService) {
    this.screener = screener;
    this.repo = repo;
    this.geometryService = geometryService;
    this.setupsRepo = setupsRepo;
    this.funnelService = funnelService;
    this.hitRateService = hitRateService;
  }

  /** Serves the persisted daily screen (default = latest date, passers only). */
  @GetMapping
  public ScreenResponse get(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      @RequestParam(defaultValue = "true") boolean passesAllOnly,
      @RequestParam(required = false) BigDecimal minRsRank,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    LocalDate date = asOf != null ? asOf : screener.latestScreenDate();
    int cappedLimit = Math.min(Math.max(1, limit), 500);
    if (date == null) {
      return new ScreenResponse(List.of(), null, 0, cappedLimit, offset);
    }
    List<TrendCandidate> rows = repo.latest(date, passesAllOnly, minRsRank, cappedLimit, offset);
    return new ScreenResponse(
        rows.stream().map(MinerviniController::toRow).toList(),
        date, repo.coverage(date), cappedLimit, offset);
  }

  /** Recomputes the screen now, persists it, and returns the fresh result. */
  @PostMapping("/run")
  public ScreenResponse run(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      @RequestParam(defaultValue = "true") boolean passesAllOnly,
      @RequestParam(required = false) BigDecimal minRsRank,
      @RequestParam(defaultValue = "50") int limit) {
    int cappedLimit = Math.min(Math.max(1, limit), 500);
    TrendTemplateService.ScreenResult res = screener.screen(asOf);
    if (res.screenDate() == null) {
      return new ScreenResponse(List.of(), null, 0, cappedLimit, 0);
    }
    repo.upsertAll(res.screenDate(), res.candidates());
    // Persist geometry for the passers too, so POST /run leaves minervini_setups consistent with the
    // screen (matches the scheduled/boot paths). Best-effort — a geometry hiccup must not 5xx the run.
    try {
      geometryService.persistForPassers(res.screenDate(), res.candidates());
    } catch (RuntimeException ignored) {
      // geometry is a diagnostic side-channel; the screen result is still authoritative
    }
    List<Row> items =
        res.candidates().stream()
            .filter(c -> !passesAllOnly || c.passesAll())
            .filter(c -> minRsRank == null || c.rsRank().compareTo(minRsRank) >= 0)
            .limit(cappedLimit)
            .map(MinerviniController::toRow)
            .toList();
    return new ScreenResponse(items, res.screenDate(), res.coverage(), cappedLimit, 0);
  }

  /**
   * Analyzer payload for one candidate (MV-5.5): the persisted gates/stage/fundamentals + freshly
   * computed VCP geometry. Works for any symbol — geometry is read from the persisted setups if the
   * symbol was a passer, else computed on demand (so a non-passer still gets a base read).
   */
  @GetMapping("/candidate/{symbol}")
  public CandidateAnalysis candidate(
      @PathVariable String symbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
    String sym = symbol.toUpperCase(java.util.Locale.ROOT);
    LocalDate date = asOf != null ? asOf : screener.latestScreenDate();
    if (date == null) {
      return new CandidateAnalysis(
          sym, "NSE", null, false, null, null, null, null, null, null, null, null, null, null,
          null, null, new boolean[0], 0, false, null, toGeometry(VcpFootprint.rejected("no data")));
    }
    VcpFootprint fp = setupsRepo.find(date, sym);
    if (fp == null) {
      fp = geometryService.detect(sym, date);
    }
    Geometry geo = toGeometry(fp);
    TrendCandidate c = repo.findOne(date, sym);
    if (c == null) {
      return new CandidateAnalysis(
          sym, "NSE", date, false, null, null, null, null, null, null, null, null, null, null,
          null, null, new boolean[0], 0, false, null, geo);
    }
    return new CandidateAnalysis(
        c.symbol(), c.exchange(), date, true, c.close(), c.sma50(), c.sma150(), c.sma200(),
        c.high52w(), c.low52w(), c.pctFromHigh(), c.pctAboveLow(), c.rsRank(), c.avgTurnover50(),
        c.freeFloatMcapCr(), c.freeFloatPct(), c.gates(), c.gatesPassed(), c.passesAll(), c.stage(),
        geo);
  }

  private static Geometry toGeometry(VcpFootprint f) {
    if (!f.vcp()) {
      return new Geometry(
          false, null, null, null, null, null, null, null, f.volumeDryUp(), f.shakeout(), null,
          null, false, f.rejectReason());
    }
    return new Geometry(
        true, f.footprint(), BigDecimal.valueOf(f.pivot()), BigDecimal.valueOf(f.deepestPct()),
        BigDecimal.valueOf(f.tightestPct()), f.contractionCount(), f.baseWeeks(),
        f.baseDurationDays(), f.volumeDryUp(), f.shakeout(), f.baseCount(),
        BigDecimal.valueOf(f.cheatPivot()), f.thrust(), null);
  }

  /**
   * The SEPA funnel three-list (MV-6.7): the day's passers ranked into immediately-buyable / on-deck
   * / watch by convergence of the Trend-Template pass + RS-rank + a valid VCP base + pivot proximity.
   */
  @GetMapping("/funnel")
  public MinerviniFunnelService.Funnel funnel(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
    LocalDate date = asOf != null ? asOf : screener.latestScreenDate();
    if (date == null) {
      return new MinerviniFunnelService.Funnel(null, null, List.of(), List.of(), List.of());
    }
    return funnelService.funnel(date);
  }

  /**
   * The screener hit-rate harness (MV-8.1): re-runs the point-in-time 8-gate screen at a weekly
   * cadence over {@code [from, to]} (defaults to the last ~3y through the latest daily bar) and
   * reports each passer's forward return at +5/+10/+21/+63 sessions vs NIFTY 50 — a per-horizon
   * hit-rate + mean excess return. Reads {@code candles}@1d (deep history), price-gates only.
   */
  @PostMapping("/backtest")
  public MinerviniHitRateService.HitRateReport backtest(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) Integer step) {
    return hitRateService.run(from, to, step);
  }

  private static Row toRow(TrendCandidate c) {
    return new Row(
        c.symbol(), c.exchange(), c.close(), c.sma50(), c.sma150(), c.sma200(),
        c.high52w(), c.low52w(), c.pctFromHigh(), c.pctAboveLow(), c.rsRank(), c.avgTurnover50(),
        c.freeFloatMcapCr(), c.freeFloatPct(), c.gates(), c.gatesPassed(), c.passesAll(), c.stage());
  }
}
