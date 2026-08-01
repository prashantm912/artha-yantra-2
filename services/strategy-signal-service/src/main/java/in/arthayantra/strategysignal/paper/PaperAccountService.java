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
      @Schema(additionalPropertiesSchema = String.class) Map<String, BigDecimal> marginPercents) {}

  private final PaperAccountRepository account;
  private final PaperPositionRepository positions;
  private final LastTickReader lastTick;
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
      InstrumentMetaClient instruments,
      MarginServiceClient margin,
      Clock clock,
      @Value("${artha.paper.margin-pct.future:0.15}") BigDecimal futureMarginPct,
      @Value("${artha.paper.margin-pct.short-option:0.12}") BigDecimal shortOptionMarginPct) {
    this.account = account;
    this.positions = positions;
    this.lastTick = lastTick;
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
      BigDecimal mark = lastTick.lastPrice(pos.exchange(), pos.tradingsymbol()).orElse(pos.avgEntryPrice());
      BigDecimal move =
          "BUY".equals(pos.side()) ? mark.subtract(pos.avgEntryPrice()) : pos.avgEntryPrice().subtract(mark);
      total = total.add(move.multiply(BigDecimal.valueOf(pos.qty())));
    }
    return total.setScale(2, RoundingMode.HALF_UP);
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
        Map.of("future", futureMarginPct, "shortOption", shortOptionMarginPct));
  }

  /** Owner edit of a book's starting capital. */
  public void updateStartingCapital(String book, BigDecimal startingCapital) {
    account.updateStartingCapital(book, startingCapital);
  }
}
