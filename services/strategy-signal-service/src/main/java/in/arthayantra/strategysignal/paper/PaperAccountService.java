package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategyengine.fills.Side;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The paper-account capital model (A12). Equity is COMPUTED on demand (starting capital + realized +
 * mark-to-market unrealized) — never stored. Capital usage per instrument class is pure config: full
 * notional for equities, premium for long options, and a margin-pct-of-notional APPROXIMATION for
 * futures &amp; short options (no Kite margin API). Buying-power warnings are non-blocking.
 */
@Service
public class PaperAccountService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  /**
   * The /paper account header payload. The two per-class maps carry {@code BigDecimal} values, which
   * ride the wire as strings exactly like every other decimal here — {@code additionalPropertiesSchema}
   * overrides the inferred map-value schema the same way {@code type = "string"} overrides a plain field.
   */
  public record AccountDto(
      @Schema(type = "string") BigDecimal startingCapital,
      @Schema(type = "string") BigDecimal cash,
      @Schema(type = "string") BigDecimal equity,
      @Schema(type = "string") BigDecimal realized,
      @Schema(type = "string") BigDecimal unrealized,
      @Schema(type = "string") BigDecimal dayPnl,
      int openPositions,
      @Schema(type = "string") BigDecimal capitalUsed,
      @Schema(additionalPropertiesSchema = String.class) Map<String, BigDecimal> usageByClass,
      @Schema(additionalPropertiesSchema = String.class) Map<String, BigDecimal> marginPercents,
      @Schema(
              description =
                  "Open positions carrying NO mark — neither a live tick nor a captured daily close"
                      + " — and therefore valued at entry cost (zero unrealized) in the figures"
                      + " above. Non-zero means equity is only partially marked.")
          int unmarkedPositions) {}

  private final PaperAccountRepository account;
  private final PaperPositionRepository positions;
  private final LastTickReader lastTick;
  private final EquityMarkCache equityMarks;
  private final InstrumentMetaClient instruments;
  private final MarginServiceClient margin;
  private final Clock clock;
  private final BigDecimal futureMarginPct;
  private final BigDecimal shortOptionMarginPct;

  /** Wires the capital model over the configured per-class margin approximations. */
  public PaperAccountService(
      PaperAccountRepository account,
      PaperPositionRepository positions,
      LastTickReader lastTick,
      EquityMarkCache equityMarks,
      InstrumentMetaClient instruments,
      MarginServiceClient margin,
      Clock clock,
      @Value("${artha.paper.margin-pct.future:0.15}") BigDecimal futureMarginPct,
      @Value("${artha.paper.margin-pct.short-option:0.12}") BigDecimal shortOptionMarginPct) {
    this.account = account;
    this.positions = positions;
    this.lastTick = lastTick;
    this.equityMarks = equityMarks;
    this.instruments = instruments;
    this.margin = margin;
    this.clock = clock;
    this.futureMarginPct = futureMarginPct;
    this.shortOptionMarginPct = shortOptionMarginPct;
  }

  /** equity = book's starting capital + Σ realized + Σ mark-to-market unrealized (never stored). */
  public BigDecimal equity(String book) {
    return account.get(book).startingCapital().add(positions.realizedTotal(book)).add(unrealizedTotal(book));
  }

  /**
   * §3.7 hero-zero: a book's accumulated REALISED profit (Σ closed-trade P&amp;L) — "your profits", the
   * funding base for the hero-zero "deploy ~10% of profits, never capital" rule. Negative when the
   * book is net-down (the caller floors the deploy to the ₹2-3k minimum then).
   */
  public BigDecimal realisedProfit(String book) {
    return positions.realizedTotal(book);
  }

  /** A book's configured account size (day-stable; the fixed-allocation base for per-account caps). */
  public BigDecimal startingCapital(String book) {
    return account.get(book).startingCapital();
  }

  /** Σ mark-to-market unrealized over a book's open positions ({@code book} null → all books). */
  public BigDecimal unrealizedTotal(String book) {
    BigDecimal total = BigDecimal.ZERO;
    for (PositionRow pos : positions.listOpen(book)) {
      BigDecimal mark = mark(pos).orElse(pos.avgEntryPrice());
      BigDecimal move =
          "BUY".equals(pos.side()) ? mark.subtract(pos.avgEntryPrice()) : pos.avgEntryPrice().subtract(mark);
      total = total.add(move.multiply(BigDecimal.valueOf(pos.qty())));
    }
    return total.setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * The mark-to-market price for one open position: the live tick when there is one, else the last
   * captured daily-bar close for a symbol that does not tick.
   *
   * <p>The second source is what this method exists for. {@code ticks:last} is written from the live
   * WS ticker, whose subscription is the futures/options universe — measured 2026-08-13, all 307
   * entries are NFO/BFO contracts and not one is an NSE cash equity. Every swing position therefore
   * missed, fell back to its own {@code avgEntryPrice}, and contributed EXACTLY ZERO unrealized, so
   * book equity was blind to +₹27,213.97 across the two swing books and every equity-denominated
   * gate — the Manas 6% open-risk cap, {@code max_deployment_pct}, {@code mode: pct} daily limits —
   * measured against a denominator missing all of it. {@link EquityMarkCache} is populated once a
   * day by the swing exit pass from the bar it already holds; see that class for why a fetch here is
   * not an option.
   *
   * <p>Tick FIRST, deliberately: for anything that genuinely ticks (every scalper option position)
   * the cache is permanently empty, so this method is byte-identical to the previous one-liner and
   * that book's behaviour is untouched. Empty ⇒ no mark of any kind — the caller decides, and every
   * caller today falls back to {@code avgEntryPrice} exactly as before, which is a ZERO unrealized
   * contribution, never a fabricated one.
   *
   * <p>No staleness gate on the tick (unchanged): this is mark-to-market, not a fill. The fill paths
   * ({@code PaperService#openOrder}, {@code #doSettle}) keep their own {@code
   * artha.paper.tick-max-age-seconds} discipline and are not touched here. The CACHE has its own,
   * separate age bound so a dead swing batch cannot serve a week-old close into book equity.
   */
  public Optional<BigDecimal> markFor(String exchange, String tradingsymbol) {
    Optional<BigDecimal> tick = lastTick.lastPrice(exchange, tradingsymbol);
    return tick.isPresent() ? tick : equityMarks.price(exchange, tradingsymbol);
  }

  /**
   * {@link #markFor} for one open position row, additionally refusing a captured close from BEFORE
   * the position existed (cross-vendor review, 2026-08-13).
   *
   * <p>A mark is only allowed to be a few sessions old, but "a few sessions" is longer than a
   * position that opened this morning has existed. Valuing it against a close from before its own
   * entry is not a stale price, it is an unrelated one — it would manufacture an unrealized P&amp;L out
   * of the gap between the two, in whichever direction the market happened to move. A live tick has
   * no such problem (it is by definition now), so this only constrains the captured-close path.
   */
  private Optional<BigDecimal> mark(PositionRow pos) {
    Optional<BigDecimal> tick = lastTick.lastPrice(pos.exchange(), pos.tradingsymbol());
    if (tick.isPresent()) {
      return tick;
    }
    return equityMarks
        .mark(pos.exchange(), pos.tradingsymbol())
        .filter(m -> !openedAfter(pos, m.session()))
        .flatMap(m -> equityMarks.price(pos.exchange(), pos.tradingsymbol()));
  }

  /** True when {@code session} closed BEFORE this position's own IST opening session. */
  private static boolean openedAfter(PositionRow pos, LocalDate session) {
    if (pos.openedAt() == null || session == null) {
      return false;
    }
    return session.isBefore(LocalDate.ofInstant(pos.openedAt().toInstant(), IST));
  }

  /**
   * How many of a book's open positions have NO mark at all — neither a live tick nor a captured
   * daily close — and are therefore being valued at their own entry price (a zero unrealized
   * contribution) inside {@link #unrealizedTotal}.
   *
   * <p>This is the visibility half of the fallback. Marking an unmarkable position at cost is the
   * SAME arithmetic as before this change and is deliberately kept (a NULL equity would break the
   * account API, the sizing path and the risk gates; refusing the entry outright would fail closed on
   * a cache that is empty after every restart, which would lock the books harder rather than
   * unlocking them). What changes is that it is no longer invisible: this count rides the account
   * payload and the {@code ay_paper_mtm_blind_positions} gauge, so "equity is fully marked" and
   * "equity cannot see part of the book" are distinguishable from outside.
   */
  public int unmarkedOpenCount(String book) {
    return (int) positions.listOpen(book).stream().filter(pos -> mark(pos).isEmpty()).count();
  }

  /**
   * Capital usage projected for one order leg (the buying-power input), SPAN-aware.
   *
   * <p>When {@code artha.margin.span-enabled=true}, futures &amp; short options are priced via the §8
   * SPAN appliance ({@code /api/v1/margin/size}); on any miss (flag off, appliance unreachable, no
   * {@code .spn} loaded, unresolved leg) it falls back to the flat margin-pct approximation. Long
   * options always use the premium; equities the full notional. Advisory only — never blocks.
   */
  public BigDecimal usageFor(
      InstrumentMeta meta, String exchange, String tradingsymbol, String side, BigDecimal price, long qty) {
    boolean spanCandidate =
        meta.instrumentClass() == InstrumentClass.FUTURE
            || (meta.instrumentClass() == InstrumentClass.OPTION && !Side.BUY.name().equals(side));
    if (spanCandidate) {
      BigDecimal span =
          margin.marginFor(exchange, tradingsymbol, side, price, qty)
              .map(MarginServiceClient.MarginEstimate::marginRequired)
              .orElse(null);
      if (span != null) {
        return span.setScale(2, RoundingMode.HALF_UP);
      }
    }
    return usageFor(meta, side, price, qty); // flat-pct fallback (disabled/unreachable/long/equity)
  }

  /** Flat margin-pct approximation (the fallback when SPAN sizing is unavailable). */
  public BigDecimal usageFor(InstrumentMeta meta, String side, BigDecimal price, long qty) {
    BigDecimal notional = price.multiply(BigDecimal.valueOf(qty));
    BigDecimal usage =
        switch (meta.instrumentClass()) {
          case EQUITY -> notional;
          case OPTION ->
              Side.BUY.name().equals(side)
                  ? notional // long option: premium paid
                  : notional.multiply(shortOptionMarginPct); // short option: margin approximation
          case FUTURE -> notional.multiply(futureMarginPct);
        };
    return usage.setScale(2, RoundingMode.HALF_UP);
  }

  /** Capital usage by class over a book's open positions ({@code book} null → all books). */
  public Map<String, BigDecimal> usageByClass(String book) {
    Map<String, BigDecimal> usage = new LinkedHashMap<>();
    usage.put("equities", BigDecimal.ZERO);
    usage.put("longOptions", BigDecimal.ZERO);
    usage.put("futuresAndShortOptions", BigDecimal.ZERO);
    for (PositionRow pos : positions.listOpen(book)) {
      InstrumentMeta meta = instruments.meta(pos.exchange(), pos.tradingsymbol());
      BigDecimal amount =
          usageFor(meta, pos.exchange(), pos.tradingsymbol(), pos.side(), pos.avgEntryPrice(), pos.qty());
      String bucket =
          switch (meta.instrumentClass()) {
            case EQUITY -> "equities";
            case OPTION -> "BUY".equals(pos.side()) ? "longOptions" : "futuresAndShortOptions";
            case FUTURE -> "futuresAndShortOptions";
          };
      usage.merge(bucket, amount, BigDecimal::add);
    }
    return usage;
  }

  /** Total capital tied up in a book's open positions ({@code book} null → all books). */
  public BigDecimal capitalUsed(String book) {
    return usageByClass(book).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** A book's free cash = equity − capital used (the buying-power budget). */
  public BigDecimal freeCash(String book) {
    return equity(book).subtract(capitalUsed(book));
  }

  /** A non-blocking warning when an order's projected usage exceeds the book's free cash. */
  public String buyingPowerWarning(String book, BigDecimal projectedUsage) {
    BigDecimal free = freeCash(book);
    if (projectedUsage.compareTo(free) > 0) {
      return "Projected capital usage "
          + projectedUsage.toPlainString()
          + " exceeds free cash "
          + free.toPlainString()
          + " (paper — not blocked)";
    }
    return null;
  }

  /** A book's day P&L = today's realized + current mark-to-market unrealized. */
  public BigDecimal dayPnl(String book) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Kolkata"));
    return positions.realizedOn(book, today).add(unrealizedTotal(book));
  }

  /** The /paper account header for a book ({@code book} null → the aggregate across all books). */
  public AccountDto account(String book) {
    BigDecimal realized = positions.realizedTotal(book);
    BigDecimal unrealized = unrealizedTotal(book);
    BigDecimal startingCapital = account.get(book).startingCapital();
    BigDecimal equity = startingCapital.add(realized).add(unrealized);
    return new AccountDto(
        startingCapital,
        freeCash(book),
        equity,
        realized,
        unrealized,
        dayPnl(book),
        positions.openCount(book),
        capitalUsed(book),
        usageByClass(book),
        Map.of("future", futureMarginPct, "shortOption", shortOptionMarginPct),
        unmarkedOpenCount(book));
  }

  /** Owner edit of a book's starting capital. */
  public void updateStartingCapital(String book, BigDecimal startingCapital) {
    account.updateStartingCapital(book, startingCapital);
  }
}
