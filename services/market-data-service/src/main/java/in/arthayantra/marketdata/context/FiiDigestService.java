package in.arthayantra.marketdata.context;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.canary.IngestHealthBoard;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * FII/DII decision-support digest (intelligence-layer design 2026-07-10 §6.1.4) — the
 * institutional-flow context read, folded from {@link FiiContextRepository} over the EXISTING
 * {@code nse_eod_*} tables and trust-gated on the {@code ingest_runs} FII sources via
 * {@link IngestHealthBoard}. No new raw data (§13 row 4).
 *
 * <p><b>Honest FII-derivative gating (A5).</b> {@code fii_derivative_stats} is empty by default when
 * {@code artha.upstox.analytics.enabled=false} (no fetcher bean, no ingest run) — a false-RED at the
 * table level. The digest distinguishes this from a real failure: an empty derivative table whose
 * {@code NSE_FII_DERIVATIVE} ingest source is not GREEN degrades to "upstox analytics disabled"
 * (available=false, DEGRADED) rather than fabricating a number or 5xx-ing.
 *
 * <p><b>Forward-only honesty (§6.5).</b> The FII index-futures long/short ratio percentile is over
 * AVAILABLE participant-OI history: it carries {@code windowSessions} and flags {@code lowConfidence}
 * below 20 sessions. <b>Batch staleness</b>: when the newest FII row is behind the last settled
 * trading day the whole digest degrades with a "stale — newest is &lt;day&gt;" reason.
 */
@Service
public class FiiDigestService {

  /** FII cash net + consecutive-day streak + a flip flag (today's sign ≠ yesterday's). */
  public record FiiCash(LocalDate tradeDate, BigDecimal net, int streakDays, String streakSide, boolean flip) {}

  /** FII vs DII cash direction — divergent when their latest nets point opposite ways. */
  public record DiiDivergence(
      @Schema(types = {"number", "null"}) BigDecimal fiiNet,
      @Schema(types = {"number", "null"}) BigDecimal diiNet,
      boolean divergent) {}

  /** FII index-futures long/short ratio + Δ vs prior + percentile over available history (§6.5). */
  public record FiiFuturesLongShort(
      LocalDate tradeDate,
      long indexLong,
      long indexShort,
      @Schema(types = {"number", "null"}) BigDecimal ratio,
      @Schema(types = {"number", "null"}) BigDecimal ratioDelta,
      @Schema(types = {"integer", "null"}) Integer percentile,
      int windowSessions,
      boolean lowConfidence) {}

  /** FII total net contracts and its day-over-day change (participant positioning). */
  public record ParticipantPositioning(
      LocalDate tradeDate,
      long netContracts,
      @Schema(types = {"integer", "null"}) Long netContractsPrior,
      @Schema(types = {"integer", "null"}) Long netChange) {}

  /** FII index-futures derivative-net availability (dead-by-default when Upstox analytics is off). */
  public record FiiDerivativeAvailability(
      boolean available,
      @Schema(types = {"string", "null"}) LocalDate tradeDate,
      @Schema(types = {"number", "null"}) BigDecimal indexFuturesNet,
      @Schema(types = {"string", "null"}) String reason) {}

  /** The FII/DII context digest. {@code dataTrust} = OK/DEGRADED/BLOCKED. */
  public record FiiDigest(
      @Schema(types = {"object", "null"}) FiiCash cash,
      @Schema(types = {"object", "null"}) DiiDivergence dii,
      @Schema(types = {"object", "null"}) FiiFuturesLongShort futuresLongShort,
      @Schema(types = {"object", "null"}) ParticipantPositioning participant,
      @Schema(types = {"object", "null"}) FiiDerivativeAvailability derivative,
      @Schema(types = {"string", "null"}) OffsetDateTime asOf,
      String dataTrust,
      List<String> trustReasons) {

    static FiiDigest blocked(String reason) {
      return new FiiDigest(null, null, null, null, null, null, "BLOCKED", List.of(reason));
    }
  }

  private static final String FII = "FII/FPI";
  private static final String DII = "DII";

  private final FiiContextRepository repo;
  private final IngestHealthBoard healthBoard;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final int lookbackSessions;
  private final int trustLookbackDays;
  private final int lowConfidenceFloor;

  /** Wires the FII fold repo, the ingest-trust board, the calendar, and the digest knobs. */
  public FiiDigestService(
      FiiContextRepository repo,
      IngestHealthBoard healthBoard,
      MarketCalendar calendar,
      Clock clock,
      @Value("${artha.context.fii.lookback-sessions:90}") int lookbackSessions,
      @Value("${artha.context.trust-lookback-days:5}") int trustLookbackDays,
      @Value("${artha.context.fii.low-confidence-floor:20}") int lowConfidenceFloor) {
    this.repo = repo;
    this.healthBoard = healthBoard;
    this.calendar = calendar;
    this.clock = clock;
    this.lookbackSessions = lookbackSessions;
    this.trustLookbackDays = trustLookbackDays;
    this.lowConfidenceFloor = lowConfidenceFloor;
  }

  /** The FII digest for {@code date} (null ⇒ the latest FII/DII cash session). */
  public FiiDigest digest(LocalDate date) {
    LocalDate session = date != null ? date : repo.latestCashSession();
    List<FiiContextRepository.ParticipantRow> participants =
        session == null ? List.of() : repo.fiiParticipantSeries(session, lookbackSessions);
    List<FiiContextRepository.CashRow> cashRows =
        session == null ? List.of() : repo.cashSeries(session, lookbackSessions * 2);
    if (cashRows.isEmpty() && participants.isEmpty()) {
      return FiiDigest.blocked("no FII/DII data captured");
    }

    List<String> reasons = new ArrayList<>();
    Map<String, String> trust = ingestTrust();
    gateSource(trust, "NSE_FII_DII", "FII/DII cash", reasons);
    gateSource(trust, "NSE_PARTICIPANT_OI", "participant OI", reasons);

    LocalDate newestData = newestDataDate(cashRows, participants);
    if (newestData != null) {
      stale(newestData, reasons);
    }

    FiiCash cash = cash(cashRows);
    DiiDivergence dii = divergence(cashRows);
    FiiFuturesLongShort fut = futuresLongShort(participants);
    ParticipantPositioning positioning = positioning(participants);
    FiiDerivativeAvailability derivative = derivative(session, trust, reasons);

    OffsetDateTime asOf =
        newestData == null ? null : newestData.atTime(MarketCalendar.SESSION_CLOSE).atOffset(Ist.OFFSET);
    String worst = reasons.isEmpty() ? "OK" : "DEGRADED";
    // A RED cash/participant source is a hole in the load-bearing input, not a soft degrade.
    if ("BLOCKED".equals(trust.get("NSE_FII_DII")) && cashRows.isEmpty()) {
      worst = "BLOCKED";
    }
    return new FiiDigest(cash, dii, fut, positioning, derivative, asOf, worst, List.copyOf(reasons));
  }

  private static LocalDate newestDataDate(
      List<FiiContextRepository.CashRow> cash, List<FiiContextRepository.ParticipantRow> part) {
    LocalDate c = cash.isEmpty() ? null : cash.get(0).tradeDate();
    LocalDate p = part.isEmpty() ? null : part.get(0).tradeDate();
    if (c == null) {
      return p;
    }
    if (p == null) {
      return c;
    }
    return c.isAfter(p) ? c : p;
  }

  private void stale(LocalDate newestData, List<String> reasons) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), Ist.ZONE);
    try {
      LocalDate floor = calendar.previousTradingDay(today);
      if (newestData.isBefore(floor)) {
        reasons.add("stale - newest FII session is " + newestData + ", last settled trading day is " + floor);
      }
    } catch (IllegalArgumentException uncoveredYear) {
      // outside calendar coverage — skip the stale check.
    }
  }

  /** FII cash net + streak (consecutive same-sign days from the latest) + flip flag. */
  private FiiCash cash(List<FiiContextRepository.CashRow> rows) {
    List<FiiContextRepository.CashRow> fii =
        rows.stream().filter(r -> FII.equals(r.category()) && r.net() != null).toList();
    if (fii.isEmpty()) {
      return null;
    }
    BigDecimal latest = fii.get(0).net();
    int latestSign = latest.signum();
    int streak = 0;
    for (FiiContextRepository.CashRow r : fii) {
      if (r.net().signum() == latestSign && latestSign != 0) {
        streak++;
      } else {
        break;
      }
    }
    boolean flip = fii.size() > 1 && fii.get(1).net().signum() != latestSign;
    String side = latestSign > 0 ? "BUY" : latestSign < 0 ? "SELL" : "FLAT";
    return new FiiCash(fii.get(0).tradeDate(), latest, streak, side, flip);
  }

  /** FII vs DII latest-net divergence (opposite signs). */
  private DiiDivergence divergence(List<FiiContextRepository.CashRow> rows) {
    BigDecimal fiiNet = latestNet(rows, FII);
    BigDecimal diiNet = latestNet(rows, DII);
    if (fiiNet == null || diiNet == null) {
      return new DiiDivergence(fiiNet, diiNet, false);
    }
    boolean divergent = fiiNet.signum() != 0 && diiNet.signum() != 0 && fiiNet.signum() != diiNet.signum();
    return new DiiDivergence(fiiNet, diiNet, divergent);
  }

  private static BigDecimal latestNet(List<FiiContextRepository.CashRow> rows, String category) {
    return rows.stream()
        .filter(r -> category.equals(r.category()) && r.net() != null)
        .map(FiiContextRepository.CashRow::net)
        .findFirst()
        .orElse(null);
  }

  /** FII index-futures long/short ratio + Δ + percentile of the latest ratio over available history. */
  private FiiFuturesLongShort futuresLongShort(List<FiiContextRepository.ParticipantRow> rows) {
    if (rows.isEmpty()) {
      return null;
    }
    // ratio per session (short == 0 ⇒ undefined); percentile is over the sessions with a defined ratio.
    Map<LocalDate, BigDecimal> ratios = new LinkedHashMap<>();
    for (FiiContextRepository.ParticipantRow r : rows) {
      if (r.indexShort() != 0) {
        ratios.put(
            r.tradeDate(),
            BigDecimal.valueOf(r.indexLong()).divide(BigDecimal.valueOf(r.indexShort()), 4, RoundingMode.HALF_UP));
      }
    }
    FiiContextRepository.ParticipantRow latest = rows.get(0);
    BigDecimal ratio = ratios.get(latest.tradeDate());
    BigDecimal prior = rows.size() > 1 ? ratios.get(rows.get(1).tradeDate()) : null;
    BigDecimal delta = ratio != null && prior != null ? ratio.subtract(prior) : null;
    int windowSessions = ratios.size();
    boolean lowConfidence = windowSessions < lowConfidenceFloor;
    Integer percentile = ratio == null ? null : percentile(ratio, ratios.values());
    return new FiiFuturesLongShort(
        latest.tradeDate(), latest.indexLong(), latest.indexShort(), ratio, delta,
        percentile, windowSessions, lowConfidence);
  }

  /** Fraction of the history at or below {@code value}, as a 0–100 integer. */
  private static Integer percentile(BigDecimal value, java.util.Collection<BigDecimal> history) {
    if (history.isEmpty()) {
      return null;
    }
    long atOrBelow = history.stream().filter(v -> v.compareTo(value) <= 0).count();
    return (int) Math.round(100.0 * atOrBelow / history.size());
  }

  private ParticipantPositioning positioning(List<FiiContextRepository.ParticipantRow> rows) {
    if (rows.isEmpty()) {
      return null;
    }
    FiiContextRepository.ParticipantRow latest = rows.get(0);
    long net = latest.totalLong() - latest.totalShort();
    Long priorNet = rows.size() > 1 ? rows.get(1).totalLong() - rows.get(1).totalShort() : null;
    Long change = priorNet == null ? null : net - priorNet;
    return new ParticipantPositioning(latest.tradeDate(), net, priorNet, change);
  }

  /** FII index-futures derivative net; empty table + a non-GREEN source ⇒ "upstox analytics disabled". */
  private FiiDerivativeAvailability derivative(
      LocalDate session, Map<String, String> trust, List<String> reasons) {
    List<FiiContextRepository.DerivativeRow> rows =
        session == null ? List.of() : repo.indexFuturesSeries(session, 2);
    if (!rows.isEmpty()) {
      return new FiiDerivativeAvailability(true, rows.get(0).tradeDate(), rows.get(0).net(), null);
    }
    String state = trust.getOrDefault("NSE_FII_DERIVATIVE", "BLOCKED");
    String reason =
        "OK".equals(state)
            ? "no FII derivative row for the session"
            : "FII derivative stats unavailable (upstox analytics disabled)";
    reasons.add(reason);
    return new FiiDerivativeAvailability(false, null, null, reason);
  }

  /** Per-source OK/DEGRADED/BLOCKED trust from the ingest health board (batch-source oracle). */
  private Map<String, String> ingestTrust() {
    Map<String, String> bySource = new LinkedHashMap<>();
    IngestHealthBoard.BoardReport board = healthBoard.board(trustLookbackDays);
    for (IngestHealthBoard.SourceHealth s : board.sources()) {
      bySource.put(s.source(), toTrust(s.status()));
    }
    return bySource;
  }

  private static void gateSource(Map<String, String> trust, String source, String label, List<String> reasons) {
    String state = trust.get(source);
    if (state != null && !"OK".equals(state)) {
      reasons.add(label + " ingest " + state.toLowerCase(java.util.Locale.ROOT) + " (source " + source + ")");
    }
  }

  private static String toTrust(String boardStatus) {
    return switch (boardStatus) {
      case "GREEN" -> "OK";
      case "YELLOW" -> "DEGRADED";
      default -> "BLOCKED";
    };
  }
}
