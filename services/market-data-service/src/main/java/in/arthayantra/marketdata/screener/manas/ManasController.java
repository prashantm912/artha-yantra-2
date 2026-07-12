package in.arthayantra.marketdata.screener.manas;

import in.arthayantra.marketdata.screener.ScreenerHistory;
import in.arthayantra.marketdata.screener.ScreenerHistoryRepository;
import in.arthayantra.marketdata.screener.ScreenerHistoryRepository.Family;
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
 * The Manas Arora selection screener surface. Returns a TYPED record (not {@code Map}) so it
 * enumerates into the springdoc contract and does not bump the market-data Map-return ratchet.
 * {@code GET} serves the persisted daily screen (fast path, the watchlist consumer);
 * {@code POST /run} recomputes on demand; {@code /funnel} is the actionable three-list;
 * {@code /candidate/{symbol}} is the per-symbol analyzer. Decimals cross the wire as JSON strings
 * (global Jackson config). Path is under {@code /api/v1/market/**} so the edge-gateway allowlist
 * already covers it. Mirrors the sibling {@code MinerviniController}.
 */
@RestController
@RequestMapping("/api/v1/market/screener/manas-arora")
public class ManasController {

  /**
   * One screener row (the 6 selection gates + universe/liquidity/float flags). {@code rsRank} is the
   * §4.1/§4.10 cross-sectional RS-rank percentile (0..100) as persisted; {@code null} when a row has
   * no rank (pre-rank rows / edge) — never fabricated.
   */
  public record Row(
      String symbol,
      String exchange,
      BigDecimal close,
      BigDecimal sma50,
      BigDecimal sma200,
      BigDecimal high52w,
      BigDecimal low52w,
      BigDecimal avgVol20,
      BigDecimal avgVol50,
      BigDecimal turnover50,
      BigDecimal withinHighPct,
      BigDecimal aboveLowPct,
      boolean withinHigh,
      boolean aboveSma50,
      boolean liquidVolume,
      boolean liquidDepth,
      boolean lowCap,
      BigDecimal freeFloatMcapCr,
      BigDecimal freeFloatPct,
      boolean[] gates,
      int gatesPassed,
      boolean passesAll,
      BigDecimal rsRank) {}

  /** The {items} envelope + as-of + coverage. */
  public record ScreenResponse(
      List<Row> items, LocalDate screenDate, int coverage, int limit, int offset) {}

  /** One base geometry setup (vcp / breakout) for a candidate. */
  public record SetupView(
      String setupType, boolean valid, String footprint, BigDecimal pivot, Integer baseWeeks,
      String rejectReason) {}

  /**
   * The full analyzer payload for one candidate: the 6 selection gates + universe/liquidity/float
   * flags (from the persisted screen row — {@code scanned=false} when the symbol was not in the
   * screen), the low-cap fundamentals, and the live vcp + breakout geometry.
   */
  public record CandidateAnalysis(
      String symbol,
      String exchange,
      LocalDate screenDate,
      boolean scanned,
      BigDecimal close,
      BigDecimal sma50,
      BigDecimal sma200,
      BigDecimal high52w,
      BigDecimal low52w,
      BigDecimal withinHighPct,
      BigDecimal aboveLowPct,
      BigDecimal avgVol20,
      BigDecimal avgVol50,
      BigDecimal turnover50,
      boolean withinHigh,
      boolean aboveSma50,
      boolean liquidVolume,
      boolean liquidDepth,
      boolean lowCap,
      BigDecimal freeFloatMcapCr,
      BigDecimal freeFloatPct,
      boolean[] gates,
      int gatesPassed,
      boolean passesAll,
      List<SetupView> setups) {}

  private final ManasScreenService screener;
  private final ManasScreenRepository repo;
  private final ManasGeometryService geometryService;
  private final ManasSetupsRepository setupsRepo;
  private final ManasFunnelService funnelService;
  private final ManasAroraBacktestService
      backtestService;
  private final ScreenerHistoryRepository history;

  /** Wires the screener + screen/geometry repositories + the funnel + swing-backtest service. */
  public ManasController(
      ManasScreenService screener,
      ManasScreenRepository repo,
      ManasGeometryService geometryService,
      ManasSetupsRepository setupsRepo,
      ManasFunnelService funnelService,
      ManasAroraBacktestService backtestService,
      ScreenerHistoryRepository history) {
    this.screener = screener;
    this.repo = repo;
    this.geometryService = geometryService;
    this.setupsRepo = setupsRepo;
    this.funnelService = funnelService;
    this.backtestService = backtestService;
    this.history = history;
  }

  /**
   * Default date for READ endpoints (screen/candidate/funnel): the latest PERSISTED screen date,
   * falling back to the bhavcopy watermark only when no screen has ever run (see the Minervini
   * twin — audit H1 2026-07-05, the empty-funnel race window).
   */
  private LocalDate defaultReadDate() {
    LocalDate persisted = repo.latestScreenDate();
    return persisted != null ? persisted : screener.latestScreenDate();
  }

  /** Serves the persisted daily screen (default = latest date, passers only). */
  @GetMapping
  public ScreenResponse get(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      @RequestParam(defaultValue = "true") boolean passesAllOnly,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    LocalDate date = asOf != null ? asOf : defaultReadDate();
    int cappedLimit = Math.min(Math.max(1, limit), 500);
    if (date == null) {
      return new ScreenResponse(List.of(), null, 0, cappedLimit, offset);
    }
    List<ManasCandidate> rows = repo.latest(date, passesAllOnly, cappedLimit, offset);
    return new ScreenResponse(
        rows.stream().map(ManasController::toRow).toList(),
        date, repo.coverage(date), cappedLimit, offset);
  }

  /** Recomputes the screen now, persists it (+ passer geometry), and returns the fresh result. */
  @PostMapping("/run")
  public ScreenResponse run(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      @RequestParam(defaultValue = "true") boolean passesAllOnly,
      @RequestParam(defaultValue = "50") int limit) {
    int cappedLimit = Math.min(Math.max(1, limit), 500);
    ManasScreenService.ScreenResult res = screener.screen(asOf);
    if (res.screenDate() == null) {
      return new ScreenResponse(List.of(), null, 0, cappedLimit, 0);
    }
    repo.upsertAll(res.screenDate(), res.candidates());
    // Persist geometry for the passers too, so POST /run leaves manas_arora_setups consistent with the
    // screen (matches the scheduled/boot paths). Best-effort — a geometry hiccup must not 5xx the run.
    try {
      geometryService.persistForPassers(res.screenDate(), res.candidates());
    } catch (RuntimeException ignored) {
      // geometry is a diagnostic side-channel; the screen result is still authoritative
    }
    List<Row> items =
        res.candidates().stream()
            .filter(c -> !passesAllOnly || c.passesAll())
            .limit(cappedLimit)
            .map(ManasController::toRow)
            .toList();
    return new ScreenResponse(items, res.screenDate(), res.coverage(), cappedLimit, 0);
  }

  /**
   * The actionable funnel three-list: the day's passers ranked into immediately-buyable / on-deck /
   * watch by convergence of the selection pass + a valid vcp/breakout base + pivot proximity.
   */
  @GetMapping("/funnel")
  public ManasFunnelService.Funnel funnel(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
    LocalDate date = asOf != null ? asOf : defaultReadDate();
    if (date == null) {
      return new ManasFunnelService.Funnel(null, null, List.of(), List.of(), List.of());
    }
    return funnelService.funnel(date);
  }

  /**
   * The recent persisted screen dates (newest first, default 30) + per-date scanned/passer counts —
   * the date-picker source for the screener time-machine (audit §6.7). Only dates that actually ran a
   * screen appear, so the picker never offers a day with no data.
   */
  @GetMapping("/dates")
  public ScreenerHistory.ScreenDatesResponse dates(@RequestParam(defaultValue = "30") int limit) {
    return new ScreenerHistory.ScreenDatesResponse(
        history.recentDates(Family.MANAS, Math.min(Math.max(1, limit), 250)));
  }

  /**
   * The day-over-day passer diff (audit §6.7): names that entered / left the all-gates-pass set
   * between {@code prior} and {@code asOf}. Defaults: {@code asOf} = the latest screen, {@code prior} =
   * the screen date immediately before it (null ⇒ the earliest screen, so everything is "entered").
   */
  @GetMapping("/diff")
  public ScreenerHistory.ScreenDiff diff(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate prior) {
    LocalDate date = asOf != null ? asOf : defaultReadDate();
    if (date == null) {
      return new ScreenerHistory.ScreenDiff(null, null, 0, 0, List.of(), List.of());
    }
    LocalDate baseline = prior != null ? prior : history.priorScreenDate(Family.MANAS, date);
    return history.diff(Family.MANAS, date, baseline);
  }

  /**
   * The funnel stage-attrition for a screen date (audit §6.7 "scanned → per-gate → buyable"): the
   * scanned count, per-individual-gate pass counts, the all-gates-pass count, and how those passers
   * split across the funnel's buyable / on-deck / watch buckets.
   */
  @GetMapping("/attrition")
  public ScreenerHistory.FunnelAttrition attrition(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
    LocalDate date = asOf != null ? asOf : defaultReadDate();
    if (date == null) {
      return new ScreenerHistory.FunnelAttrition(null, 0, List.of(), 0, 0, 0, 0);
    }
    ScreenerHistory.AttritionCounts counts = history.attrition(Family.MANAS, date);
    ManasFunnelService.Funnel funnel = funnelService.funnel(date);
    return new ScreenerHistory.FunnelAttrition(
        date, counts.scanned(), counts.gates(), counts.passesAll(),
        funnel.immediatelyBuyable().size(), funnel.onDeck().size(), funnel.watch().size());
  }

  /**
   * Analyzer payload for one candidate: the persisted gates/flags/fundamentals + freshly read base
   * geometry (both setups). Works for any symbol — geometry is read from the persisted setups if the
   * symbol was a passer, else computed on demand (so a non-passer still gets a base read).
   */
  @GetMapping("/candidate/{symbol}")
  public CandidateAnalysis candidate(
      @PathVariable String symbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
    String sym = symbol.toUpperCase(java.util.Locale.ROOT);
    LocalDate date = asOf != null ? asOf : defaultReadDate();
    if (date == null) {
      return new CandidateAnalysis(
          sym, "NSE", null, false, null, null, null, null, null, null, null, null, null, null,
          false, false, false, false, false, null, null, new boolean[0], 0, false, List.of());
    }
    List<ManasSetup> setups = setupsRepo.find(date, sym);
    if (setups.isEmpty()) {
      setups = geometryService.detect(sym, date);
    }
    List<SetupView> setupViews = setups.stream().map(ManasController::toSetupView).toList();
    ManasCandidate c = repo.findOne(date, sym);
    if (c == null) {
      return new CandidateAnalysis(
          sym, "NSE", date, false, null, null, null, null, null, null, null, null, null, null,
          false, false, false, false, false, null, null, new boolean[0], 0, false, setupViews);
    }
    return new CandidateAnalysis(
        c.symbol(), c.exchange(), date, true, c.close(), c.sma50(), c.sma200(), c.high52w(),
        c.low52w(), c.withinHighPct(), c.aboveLowPct(), c.avgVol20(), c.avgVol50(), c.turnover50(),
        c.withinHigh(), c.aboveSma50(), c.liquidVolume(), c.liquidDepth(), c.lowCap(),
        c.freeFloatMcapCr(), c.freeFloatPct(), c.gates(), c.gatesPassed(), c.passesAll(), setupViews);
  }

  /**
   * The Manas swing backtest — a faithful event-driven replay of the two setups (breakout + vcp
   * pivot entry, ATR-stop / ATR-trail / fast-move / parabolic exit, pyramiding) over ~11y of {@code
   * candles}@1d, giving trade-level win-rate / payoff / expectancy the screener alone cannot.
   * Compute is minutes, so POST kicks it off on a background thread (returns the {@code running}
   * report); GET reads the latest result.
   */
  @PostMapping("/swing-backtest")
  public ManasAroraBacktestService.Report
      triggerSwingBacktest(@RequestParam(required = false) Integer years) {
    backtestService.trigger(years);
    return latestOrIdle();
  }

  /** The latest swing-backtest report (or an {@code idle} placeholder before the first run). */
  @GetMapping("/swing-backtest")
  public ManasAroraBacktestService.Report
      swingBacktest() {
    return latestOrIdle();
  }

  /**
   * The latest multi-variant swing-backtest comparison — technical / rs / turnover / rs-turnover /
   * pyramiding A/B side by side, with per-variant portfolio stats (annual/monthly returns, CAGR,
   * drawdown, Sharpe) and the slot sweep. Isolates whether the RS-rank gate, the turnover floor, and
   * add-to-winner each sharpen the edge.
   */
  @GetMapping("/swing-backtest/compare")
  public ManasAroraBacktestService.BacktestResult
      swingBacktestCompare() {
    ManasAroraBacktestService.BacktestResult r =
        backtestService.latestResult();
    return r != null
        ? r
        : new ManasAroraBacktestService
            .BacktestResult(
            "idle", null, null, List.of(), List.of(), "no backtest run yet");
  }

  private ManasAroraBacktestService.Report
      latestOrIdle() {
    ManasAroraBacktestService.Report r =
        backtestService.latest();
    return r != null
        ? r
        : new ManasAroraBacktestService.Report(
            "idle", null, null, null, 0, 0, List.of(), null, null, null, "no backtest run yet");
  }

  private static SetupView toSetupView(ManasSetup s) {
    return new SetupView(
        s.setupType(), s.valid(), s.footprint(),
        s.valid() ? BigDecimal.valueOf(s.pivot()) : null, s.valid() ? s.baseWeeks() : null,
        s.rejectReason());
  }

  private static Row toRow(ManasCandidate c) {
    return new Row(
        c.symbol(), c.exchange(), c.close(), c.sma50(), c.sma200(), c.high52w(), c.low52w(),
        c.avgVol20(), c.avgVol50(), c.turnover50(), c.withinHighPct(), c.aboveLowPct(),
        c.withinHigh(), c.aboveSma50(), c.liquidVolume(), c.liquidDepth(), c.lowCap(),
        c.freeFloatMcapCr(), c.freeFloatPct(), c.gates(), c.gatesPassed(), c.passesAll(), c.rsRank());
  }
}
