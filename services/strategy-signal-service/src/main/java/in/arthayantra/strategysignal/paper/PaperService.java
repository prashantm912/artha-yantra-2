package in.arthayantra.strategysignal.paper;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.strategyengine.fills.FillSimulator.Fill;
import in.arthayantra.strategyengine.fills.Side;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import in.arthayantra.strategysignal.signals.SignalRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The paper-trading ledger (Q1). Orders fill through the shared {@link PaperFillService} (engine
 * JAR), positions average on the §F.6 partial-unique open key, realized P&amp;L is the exact signed
 * cash of the entry + exit legs (both priced by the JAR), and unrealized P&amp;L is computed live
 * from the Redis last-tick map — never stored.
 */
@Service
public class PaperService {

  private static final Logger log = LoggerFactory.getLogger(PaperService.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  /**
   * Open-order request (from a signal, or a manual entry); optional SL/TP bracket levels. {@code book}
   * is the paper book to charge; null → resolved from the signal's strategy family, or {@code MANUAL}
   * for a hand order with no signal.
   */
  public record OrderRequest(
      Long signalId,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal price,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      Integer subaccountIdx,
      String book) {

    /** E10 9-arg form: sub-account set, book resolved from the signal. */
    public OrderRequest(
        Long signalId,
        String exchange,
        String tradingsymbol,
        String side,
        long qty,
        BigDecimal price,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        Integer subaccountIdx) {
      this(signalId, exchange, tradingsymbol, side, qty, price, stopLoss, takeProfit, subaccountIdx, null);
    }

    /** Pre-E10 8-arg form: no sub-account (manual / non-scalper order leaves the ledger key NULL). */
    public OrderRequest(
        Long signalId,
        String exchange,
        String tradingsymbol,
        String side,
        long qty,
        BigDecimal price,
        BigDecimal stopLoss,
        BigDecimal takeProfit) {
      this(signalId, exchange, tradingsymbol, side, qty, price, stopLoss, takeProfit, null, null);
    }
  }

  /** An open position with its live mark-to-market (and an optional buying-power warning on open). */
  public record PositionDto(
      long id,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal avgEntryPrice,
      BigDecimal markPrice,
      BigDecimal unrealizedPnl,
      BigDecimal realizedPnl,
      String status,
      OffsetDateTime openedAt,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      String buyingPowerWarning) {

    /** A copy carrying the non-blocking buying-power warning (A12). */
    PositionDto withWarning(String warning) {
      return new PositionDto(
          id, exchange, tradingsymbol, side, qty, avgEntryPrice, markPrice, unrealizedPnl,
          realizedPnl, status, openedAt, stopLoss, takeProfit, warning);
    }
  }

  /** A closed trade. */
  public record TradeDto(
      long id,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal avgEntryPrice,
      BigDecimal realizedPnl,
      OffsetDateTime openedAt,
      OffsetDateTime closedAt) {}

  private final PaperOrderRepository orders;
  private final PaperPositionRepository positions;
  private final PaperFillService fills;
  private final LastTickReader lastTick;
  private final InstrumentMetaClient instruments;
  private final SignalRepository signals;
  private final PaperAccountService accountService;
  private final BookResolver books;
  private final ApplicationEventPublisher events;

  /** Wires the ledger collaborators. */
  public PaperService(
      PaperOrderRepository orders,
      PaperPositionRepository positions,
      PaperFillService fills,
      LastTickReader lastTick,
      InstrumentMetaClient instruments,
      SignalRepository signals,
      PaperAccountService accountService,
      BookResolver books,
      ApplicationEventPublisher events) {
    this.orders = orders;
    this.positions = positions;
    this.fills = fills;
    this.lastTick = lastTick;
    this.instruments = instruments;
    this.signals = signals;
    this.accountService = accountService;
    this.books = books;
    this.events = events;
  }

  /** Simulates an entry; fills via {@code ltp_slippage/v1} against the next-tick LTP + cost model. */
  @Transactional
  public PositionDto openOrder(OrderRequest request) {
    String exchange = request.exchange();
    String tradingsymbol = request.tradingsymbol();
    String side = request.side();
    BigDecimal signalEntry = null;
    if (request.signalId() != null) {
      SignalRepository.SignalRow signal =
          signals
              .find(request.signalId())
              .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND_SIGNAL, "no such signal"));
      // Prefer the request's EXPLICIT instrument when given (the #11 straddle opens its PE leg with an
      // explicit symbol/side/price while still linking the fill to the parent signal); fall back to the
      // signal's primary leg otherwise — backward-identical for every directional take (passes nulls).
      exchange = exchange != null ? exchange : signal.exchange();
      tradingsymbol = tradingsymbol != null ? tradingsymbol : signal.tradingsymbol();
      side = side != null ? side : signal.side();
      signalEntry = signal.entryPrice();
    }
    if (exchange == null || tradingsymbol == null || side == null) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "exchange, tradingsymbol and side are required");
    }
    if (request.qty() <= 0) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "qty must be positive");
    }
    BigDecimal reference =
        firstNonNull(
            request.price(), lastTick.lastPrice(exchange, tradingsymbol).orElse(null), signalEntry);
    if (reference == null) {
      throw new ApiException(422, ErrorCodes.DATA_STALE, "no price available to fill " + exchange + ":" + tradingsymbol);
    }
    // The book to charge: explicit on the request, else the signal's strategy family, else MANUAL.
    String book =
        request.book() != null
            ? request.book()
            : request.signalId() != null
                ? books.bookForSignal(request.signalId())
                : BookResolver.MANUAL;
    InstrumentMeta meta = instruments.meta(exchange, tradingsymbol);
    Fill fill = fills.fill(Side.valueOf(side), request.qty(), reference, meta);
    orders.insertFilled(
        book, request.signalId(), exchange, tradingsymbol, side, request.qty(), fill.fillPrice(),
        fills.simulatorId(), fill.slippageApplied(), null, null);
    upsertPosition(
        book, exchange, tradingsymbol, side, request.qty(), fill.fillPrice(),
        request.stopLoss(), request.takeProfit(), request.subaccountIdx());
    String warning =
        accountService.buyingPowerWarning(
            book,
            accountService.usageFor(
                meta, exchange, tradingsymbol, side, fill.fillPrice(), request.qty()));
    return positions
        .findOpen(book, exchange, tradingsymbol, side)
        .map(row -> toPositionDto(row).withWarning(warning))
        .orElseThrow(() -> new ApiException(500, ErrorCodes.INTERNAL_ERROR, "position not opened"));
  }

  private void upsertPosition(
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal fillPrice,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      Integer subaccountIdx) {
    Optional<PositionRow> existing = positions.findOpen(book, exchange, tradingsymbol, side);
    if (existing.isPresent()) {
      // averaging onto an open position keeps its original bracket levels AND its original
      // sub-account (set at first open) — a later add never re-charges the trade to a new account.
      PositionRow row = existing.get();
      long newQty = row.qty() + qty;
      BigDecimal newAvg =
          row.avgEntryPrice()
              .multiply(BigDecimal.valueOf(row.qty()))
              .add(fillPrice.multiply(BigDecimal.valueOf(qty)))
              .divide(BigDecimal.valueOf(newQty), 4, RoundingMode.HALF_UP);
      positions.updateOpen(row.id(), newQty, newAvg);
    } else {
      positions.insertOpen(
          book, exchange, tradingsymbol, side, qty, fillPrice, stopLoss, takeProfit, subaccountIdx);
    }
  }

  /** Closes a position at the stated price (or the last tick); realized = exit + entry-basis cash. */
  @Transactional
  public TradeDto closePosition(long id, BigDecimal price) {
    PositionRow pos =
        positions.find(id).orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND_RESOURCE, "no such position"));
    if (!"OPEN".equals(pos.status())) {
      throw new ApiException(409, ErrorCodes.CONFLICT_POSITION_CLOSED, "position already closed");
    }
    settle(pos, price, "MANUAL");
    return positions
        .find(id)
        .map(this::toTradeDto)
        .orElseThrow(() -> new ApiException(500, ErrorCodes.INTERNAL_ERROR, "close failed"));
  }

  /** The shared close path (used by manual close, the 15:45 sweep, and expiry settlement). */
  BigDecimal settle(PositionRow pos, BigDecimal price, String closeReason) {
    return doSettle(pos, price, closeReason, false);
  }

  /** Expiry settlement at intrinsic/spot — exercise STT leg, no slippage (Phase 43B). */
  BigDecimal settleExpiry(PositionRow pos, BigDecimal reference, boolean exercise) {
    return doSettle(pos, reference, "EXPIRY_SETTLEMENT", exercise);
  }

  private BigDecimal doSettle(PositionRow pos, BigDecimal price, String closeReason, boolean exercise) {
    InstrumentMeta meta = instruments.meta(pos.exchange(), pos.tradingsymbol());
    Side exitSide = "BUY".equals(pos.side()) ? Side.SELL : Side.BUY;
    BigDecimal reference =
        firstNonNull(price, lastTick.lastPrice(pos.exchange(), pos.tradingsymbol()).orElse(null), pos.avgEntryPrice());
    Fill exit =
        exercise
            ? fills.settlementFill(exitSide, pos.qty(), reference, meta)
            : fills.fill(exitSide, pos.qty(), reference, meta);
    Fill entryBasis = fills.costBasis(Side.valueOf(pos.side()), pos.qty(), pos.avgEntryPrice(), meta);
    BigDecimal realized = exit.netValue().add(entryBasis.netValue()).setScale(4, RoundingMode.HALF_UP);
    orders.insertFilled(
        pos.book(), null, pos.exchange(), pos.tradingsymbol(), exitSide.name(), pos.qty(), exit.fillPrice(),
        fills.simulatorId(), exit.slippageApplied(), null, null);
    positions.close(pos.id(), realized, closeReason);
    // Auto-journal hook: the journal module listens AFTER_COMMIT (so a journal failure can never
    // roll back the close). Publishing inside the close tx is fine — delivery is deferred to commit.
    events.publishEvent(new PaperPositionClosed(pos.id(), realized, closeReason));
    return realized;
  }

  /** Open positions with mark-to-market P&amp;L ({@code book} null → all books; unrealized never stored). */
  public List<PositionDto> openPositions(String book) {
    return positions.listOpen(book).stream().map(this::toPositionDto).toList();
  }

  /** The closed-trade ledger for a book ({@code book} null → all; optionally one tradingsymbol). */
  public List<TradeDto> trades(
      String book, OffsetDateTime from, OffsetDateTime to, String tradingsymbol, int limit, int offset) {
    return positions.listClosed(book, from, to, tradingsymbol, limit, offset).stream()
        .map(this::toTradeDto)
        .toList();
  }

  /** Daily realized-equity curve + win rate / expectancy over a book's closed trades (null → all). */
  public Map<String, Object> pnl(String book) {
    List<PositionRow> closed = positions.listClosed(book, null, null, null, 500, 0);
    // listClosed is newest-first; walk oldest-first for the cumulative curve
    List<PositionRow> chrono = new ArrayList<>(closed);
    java.util.Collections.reverse(chrono);
    Map<LocalDate, BigDecimal> byDay = new java.util.TreeMap<>();
    BigDecimal realizedTotal = BigDecimal.ZERO;
    int wins = 0;
    for (PositionRow row : chrono) {
      realizedTotal = realizedTotal.add(row.realizedPnl());
      if (row.realizedPnl().signum() > 0) {
        wins++;
      }
      LocalDate day = row.closedAt() == null ? LocalDate.now(IST) : row.closedAt().atZoneSameInstant(IST).toLocalDate();
      byDay.merge(day, row.realizedPnl(), BigDecimal::add);
    }
    List<Map<String, Object>> points = new ArrayList<>();
    BigDecimal cumulative = BigDecimal.ZERO;
    for (Map.Entry<LocalDate, BigDecimal> entry : byDay.entrySet()) {
      cumulative = cumulative.add(entry.getValue());
      points.add(Map.of("date", entry.getKey().toString(), "equity", cumulative));
    }
    int total = chrono.size();
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("realizedTotal", realizedTotal.setScale(2, RoundingMode.HALF_UP));
    summary.put("trades", total);
    summary.put("winRate", total == 0 ? null : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP));
    summary.put(
        "expectancy",
        total == 0 ? null : realizedTotal.divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP));
    return Map.of("points", points, "summary", summary);
  }

  /** Wipes a book's paper ledger ({@code book} null → all books; confirm-guarded). */
  @Transactional
  public void reset(String book, boolean confirm) {
    if (!confirm) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "reset requires confirm=true");
    }
    positions.deleteAll(book);
    orders.deleteAll(book);
  }

  /**
   * Closes every OPEN position linked to a signal — the engine-EXIT hook (a TAKEN entry's exit must
   * resolve its paper position). Settles at the instrument's live LTP (the null-price fallback in
   * {@code doSettle}), with the engine's exit cause as {@code close_reason}. No-op when nothing is
   * open for the signal.
   */
  @Transactional
  public int closeForSignal(long signalId, String closeReason) {
    return closeForSignal(signalId, closeReason, null);
  }

  /**
   * Closes every OPEN position linked to a signal at an EXPLICIT settlement price ({@code null} → the
   * live-LTP fallback in {@code doSettle}). The Phase-9 Minervini swing batch passes the fresh
   * daily-bar close: its equities do not tick, so the LTP fallback would otherwise settle every swing
   * close at the entry price (breakeven) and lose the real daily-close exit.
   */
  @Transactional
  public int closeForSignal(long signalId, String closeReason, BigDecimal price) {
    int closed = 0;
    for (PositionRow pos : positions.openForSignal(signalId)) {
      try {
        settle(pos, price, closeReason);
        closed++;
      } catch (Exception e) {
        log.warn("signal-exit close failed for position {}: {}", pos.id(), e.getMessage());
      }
    }
    return closed;
  }

  /** 15:45 IST mark-to-close: settle every OPEN intraday position so it does not carry overnight. */
  @Transactional
  public int markToCloseIntraday() {
    int closed = 0;
    for (PositionRow pos : positions.intradayOpen()) {
      try {
        settle(pos, null, "INTRADAY_MTM");
        closed++;
      } catch (Exception e) {
        log.warn("mark-to-close failed for position {}: {}", pos.id(), e.getMessage());
      }
    }
    return closed;
  }

  private PositionDto toPositionDto(PositionRow row) {
    BigDecimal mark = lastTick.lastPrice(row.exchange(), row.tradingsymbol()).orElse(null);
    BigDecimal unrealized = null;
    if (mark != null) {
      BigDecimal move =
          "BUY".equals(row.side())
              ? mark.subtract(row.avgEntryPrice())
              : row.avgEntryPrice().subtract(mark);
      unrealized = move.multiply(BigDecimal.valueOf(row.qty())).setScale(2, RoundingMode.HALF_UP);
    }
    return new PositionDto(
        row.id(), row.exchange(), row.tradingsymbol(), row.side(), row.qty(), row.avgEntryPrice(),
        mark, unrealized, row.realizedPnl(), row.status(), row.openedAt(),
        row.stopLoss(), row.takeProfit(), null);
  }

  private TradeDto toTradeDto(PositionRow row) {
    return new TradeDto(
        row.id(), row.exchange(), row.tradingsymbol(), row.side(), row.qty(), row.avgEntryPrice(),
        row.realizedPnl(), row.openedAt(), row.closedAt());
  }

  private static BigDecimal firstNonNull(BigDecimal... values) {
    for (BigDecimal value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }
}
