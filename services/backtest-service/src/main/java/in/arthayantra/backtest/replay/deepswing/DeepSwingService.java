package in.arthayantra.backtest.replay.deepswing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.dispatch.JobCancelledException;
import in.arthayantra.backtest.dispatch.JobStreamDispatcher;
import in.arthayantra.backtest.dispatch.ProgressPublisher;
import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.replay.RunRepository;
import in.arthayantra.backtest.replay.Trade;
import in.arthayantra.backtest.replay.TradeRepository;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategyengine.fills.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The DEEP_SWING job type (research-fidelity audit P0-3): the swing deep-sim lineage via the
 * job-pipeline route. {@link #submit} validates + pins a self-contained DEEP_SWING job onto the
 * existing backtest queue; {@link #run} (called from {@code BacktestRunner} for a DEEP_SWING job)
 * CALLS market-data's deep-sim over HTTP (the sim STAYS where its ~11-year universe lives — never
 * ported) and persists the outcome into the SHIPPED backtest lineage: a {@code backtest_runs} row
 * (headline metrics + the FULL deep-sim report kept intact in the metrics JSONB) + per-trade {@code
 * backtest_trades} rows, stamped with the market-data engine SHA (V008) + the job's actor (V009).
 *
 * <p>Rides the same worker pool + {@code jobs.backtest} stream as a BACKTEST (the COUNTERFACTUAL
 * precedent) and is admission-capped per kind ({@code artha.backtest.max-queued}); the ACTUAL
 * concurrency bound is market-data's single-permit {@code SwingBacktestGate} (≤1 deep-sim computes at
 * once). Because it writes {@code backtest_runs}/{@code backtest_trades}, the standard {@code /results}
 * + {@code /trades} + {@code experiment_runs} surfaces serve it for free — no new result table.
 */
@Service
public class DeepSwingService {

  private static final Logger log = LoggerFactory.getLogger(DeepSwingService.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final BigDecimal DEFAULT_CAPITAL = new BigDecimal("1000000");

  private final JobRepository jobs;
  private final JobStreamDispatcher dispatcher;
  private final ProgressPublisher progress;
  private final MarketDataDeepSwingClient client;
  private final RunRepository runs;
  private final TradeRepository trades;
  private final ObjectMapper objectMapper;
  private final int maxQueued;

  /** Wires the collaborators. */
  public DeepSwingService(
      JobRepository jobs,
      JobStreamDispatcher dispatcher,
      ProgressPublisher progress,
      MarketDataDeepSwingClient client,
      RunRepository runs,
      TradeRepository trades,
      ObjectMapper objectMapper,
      @Value("${artha.backtest.max-queued:500}") int maxQueued) {
    this.jobs = jobs;
    this.dispatcher = dispatcher;
    this.progress = progress;
    this.client = client;
    this.runs = runs;
    this.trades = trades;
    this.objectMapper = objectMapper;
    this.maxQueued = maxQueued;
  }

  // ---- submission -----------------------------------------------------------------------------

  /**
   * Validates the request, pins a self-contained DEEP_SWING job ({@code {family, from, variant}} in the
   * request JSONB, no strategy version) and dispatches it onto the existing backtest queue. Returns the
   * queued job.
   */
  public Job submit(DeepSwingRunRequest req, String correlationId) {
    if (req == null || req.family() == null || req.family().isBlank()) {
      throw badRequest("family is required (manas | minervini)");
    }
    String family = req.family().trim().toLowerCase(Locale.ROOT);
    if (!"manas".equals(family) && !"minervini".equals(family)) {
      throw badRequest("family must be 'manas' or 'minervini'; got " + req.family());
    }
    LocalDate from = parseFrom(req.from());
    // IST "today" — the deep-sim's calendar is the exchange's, and container-UTC is behind IST.
    if (!from.isBefore(LocalDate.now(IST))) {
      throw badRequest("from must be a past date; got " + from);
    }
    String variant =
        (req.variant() == null || req.variant().isBlank()) ? null : req.variant().trim();
    // Admission cap AFTER validation, so garbage input is always a 422 even when saturated.
    long queued = jobs.countQueued(JobKind.DEEP_SWING);
    if (queued >= maxQueued) {
      throw new ApiException(
          429,
          ErrorCodes.RATE_LIMIT_QUEUE,
          "deep-swing queue is saturated (" + queued + " queued, cap " + maxQueued + ")");
    }

    ObjectNode request = objectMapper.createObjectNode();
    request.put("deepSwing", true);
    request.put("family", family);
    request.put("from", from.toString());
    if (variant != null) {
      request.put("variant", variant);
    }
    Job job = jobs.insertQueued(JobKind.DEEP_SWING, null, null, request, correlationId, "owner");
    dispatcher.dispatchBacktest(job.id());
    progress.refreshSummary();
    return job;
  }

  // ---- worker execution -----------------------------------------------------------------------

  /**
   * Executes the pinned DEEP_SWING job: calls market-data's deep-sim over HTTP (minutes), maps the
   * result into one {@code backtest_runs} row + its {@code backtest_trades} rows. Called from {@code
   * BacktestRunner} on the worker thread. Throws {@link JobCancelledException} on cancel.
   */
  public void run(Job job, IntConsumer progressSink, BooleanSupplier cancelled) {
    JsonNode request = job.request();
    String family = request.path("family").asText();
    LocalDate from = LocalDate.parse(request.path("from").asText());
    String variant = request.path("variant").asText(null);
    progressSink.accept(5);

    // The long synchronous call — the deep-sim runs where its ~11-year universe lives.
    DeepSwingReply reply = client.run(family, from, variant);
    if (cancelled.getAsBoolean()) {
      throw new JobCancelledException(job.id());
    }
    DeepSwingReply.Result r = reply == null ? null : reply.result();
    if (r == null) {
      throw new ApiException(
          502, ErrorCodes.UPSTREAM_UNAVAILABLE, "deep-sim returned no result for " + family);
    }
    progressSink.accept(85);

    BigDecimal capital = r.capital() == null ? DEFAULT_CAPITAL : r.capital();
    BigDecimal totalReturn = r.totalReturnPct() == null ? BigDecimal.ZERO : r.totalReturnPct();
    BigDecimal finalEquity =
        capital
            .multiply(BigDecimal.ONE.add(totalReturn.movePointLeft(2)))
            .setScale(2, RoundingMode.HALF_UP);
    OffsetDateTime startTs = from.atStartOfDay(IST).toOffsetDateTime();
    OffsetDateTime endTs = OffsetDateTime.now(IST);
    List<Trade> tradeRows = toTrades(r.trades());

    UUID runId =
        runs.insertDeepSwing(
            job.id(),
            "SWING",
            family.toUpperCase(Locale.ROOT) + ":" + r.variant(),
            startTs,
            endTs,
            capital,
            finalEquity,
            totalReturn,
            nz(r.sharpe()),
            nz(r.maxDrawdownPct()),
            nz(r.winRatePct()),
            nz(r.profitFactor()),
            // ONE statistical basis for the headline columns (review MED): trade_count = the
            // portfolio's tradesTaken — the SAME basis as total_return/sharpe/max_drawdown, so a
            // consumer pairing N with sharpe (deflated-Sharpe DOF, EVO scoring) is consistent. The
            // FULL closed signal-lot set (incl. portfolio-skipped) still rides backtest_trades; its
            // count is metrics.signalLotCount.
            r.tradesTaken(),
            buildMetrics(r, tradeRows.size()),
            "deep-swing:" + family + ":" + r.variant(),
            "deep-swing/" + family,
            reply.engineSha(),
            reply.engineImage(),
            job.createdBy());
    trades.insertAll(runId, tradeRows);

    progressSink.accept(100);
    log.info(
        "deep-swing run {} completed: {}/{} — {} trades, {} scanned",
        runId, family, r.variant(), tradeRows.size(), r.symbolsScanned());
  }

  /** The run's metrics JSONB: the flat {@code cagr}/{@code totalReturn} keys the candle path also
   * writes (experiment_runs reads {@code metrics->>'cagr'}), provenance, honest caveats (including
   * the EXPLICIT statistical basis of each headline column — review MED), and the FULL deep-sim
   * report kept intact so a swing doctrine number is reproducible from the run row alone. */
  private ObjectNode buildMetrics(DeepSwingReply.Result r, int signalLotCount) {
    ObjectNode m = objectMapper.createObjectNode();
    putDecimal(m, "cagr", r.cagrPct());
    putDecimal(m, "totalReturn", r.totalReturnPct());
    m.put("family", r.family());
    m.put("variant", r.variant());
    m.put("symbolsScanned", r.symbolsScanned());
    m.put("tradesTaken", r.tradesTaken());
    m.put("tradesSkipped", r.tradesSkipped());
    m.put("signalLotCount", signalLotCount);
    putDecimal(m, "capital", r.capital());
    ArrayNode caveats = m.putArray("caveats");
    caveats.add(
        "Swing deep-sim (audit P0-3): survivorship-biased NSE-EQ universe; headline = RS-priority NET"
            + " portfolio (the realistic-live estimate).");
    caveats.add(
        "Statistical bases: total_return/sharpe/max_drawdown/trade_count = the slot-limited RS-priority"
            + " NET portfolio (tradesTaken); backtest_trades rows = the variant's FULL closed signal-lot"
            + " set incl. portfolio-skipped (signalLotCount), per-1-share gross; win_rate/profit_factor ="
            + " gross per-trade stats over that full lot set.");
    caveats.add(
        "Frictionless per-trade fills (entry+exit at the signal bar's own close); portfolio-level cost"
            + " netting only (audit B2).");
    caveats.add(
        "Sortino not computed by the deep-sim (0); equity/drawdown curves are aggregate-only ([]) —"
            + " see the report block for annual/monthly returns.");
    if (r.report() != null && !r.report().isNull()) {
      m.set("report", r.report());
    }
    return m;
  }

  /** Maps the deep-sim wire trades to backtest_trades rows on a 1-share basis (the sim is a % model). */
  private static List<Trade> toTrades(List<DeepSwingReply.Trade> src) {
    List<Trade> out = new ArrayList<>();
    if (src == null) {
      return out;
    }
    int seq = 0;
    for (DeepSwingReply.Trade t : src) {
      OffsetDateTime entryTs =
          t.entryDate() == null ? null : t.entryDate().atStartOfDay(IST).toOffsetDateTime();
      OffsetDateTime exitTs =
          t.exitDate() == null ? null : t.exitDate().atStartOfDay(IST).toOffsetDateTime();
      BigDecimal entryPrice = scale(t.entryPrice(), 4);
      BigDecimal exitPrice = scale(t.exitPrice(), 4);
      // Per-1-share P&L (the deep-sim exposes no share count / rupee P&L per lot; costs are
      // portfolio-level, so this per-trade figure is gross — consistent with pnl_pct below).
      BigDecimal pnl =
          (entryPrice == null || exitPrice == null)
              ? BigDecimal.ZERO
              : exitPrice.subtract(entryPrice).setScale(2, RoundingMode.HALF_UP);
      BigDecimal pnlPct = t.pnlPct() == null ? BigDecimal.ZERO : t.pnlPct().setScale(6, RoundingMode.HALF_UP);
      out.add(
          new Trade(
              ++seq,
              Side.BUY, // Manas + Minervini are long-only swing methods
              1L,
              entryTs,
              entryPrice,
              exitTs,
              exitPrice,
              pnl,
              pnlPct,
              t.exitReason(), // the deep-sim's REAL exit attribution (candle path lacks this — B11)
              t.barsHeld(),
              null,
              null,
              "NSE",
              t.symbol(),
              null,
              null));
    }
    return out;
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static BigDecimal scale(BigDecimal v, int scale) {
    return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
  }

  private static void putDecimal(ObjectNode node, String key, BigDecimal value) {
    if (value == null) {
      node.putNull(key);
    } else {
      node.put(key, value.toPlainString());
    }
  }

  private static LocalDate parseFrom(String raw) {
    if (raw == null || raw.isBlank()) {
      throw badRequest("from is required (ISO date, e.g. 2015-01-01)");
    }
    String v = raw.trim();
    try {
      return LocalDate.parse(v);
    } catch (DateTimeParseException e) {
      try {
        return OffsetDateTime.parse(v).toLocalDate();
      } catch (DateTimeParseException e2) {
        throw badRequest("from is not an ISO date/date-time: " + raw);
      }
    }
  }

  private static ApiException badRequest(String message) {
    return new ApiException(422, ErrorCodes.VALIDATION_FAILED, message);
  }
}
