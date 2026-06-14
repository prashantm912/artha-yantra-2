package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategyengine.fills.Side;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
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

  /** The /paper account header payload. */
  public record AccountDto(
      BigDecimal startingCapital,
      BigDecimal cash,
      BigDecimal equity,
      BigDecimal realized,
      BigDecimal unrealized,
      BigDecimal dayPnl,
      int openPositions,
      BigDecimal capitalUsed,
      Map<String, BigDecimal> usageByClass,
      Map<String, BigDecimal> marginPercents) {}

  private final PaperAccountRepository account;
  private final PaperPositionRepository positions;
  private final LastTickReader lastTick;
  private final InstrumentMetaClient instruments;
  private final Clock clock;
  private final BigDecimal futureMarginPct;
  private final BigDecimal shortOptionMarginPct;

  /** Wires the capital model over the configured per-class margin approximations. */
  public PaperAccountService(
      PaperAccountRepository account,
      PaperPositionRepository positions,
      LastTickReader lastTick,
      InstrumentMetaClient instruments,
      Clock clock,
      @Value("${artha.paper.margin-pct.future:0.15}") BigDecimal futureMarginPct,
      @Value("${artha.paper.margin-pct.short-option:0.12}") BigDecimal shortOptionMarginPct) {
    this.account = account;
    this.positions = positions;
    this.lastTick = lastTick;
    this.instruments = instruments;
    this.clock = clock;
    this.futureMarginPct = futureMarginPct;
    this.shortOptionMarginPct = shortOptionMarginPct;
  }

  /** equity = starting capital + Σ realized + Σ mark-to-market unrealized (never stored). */
  public BigDecimal equity() {
    return account.get().startingCapital().add(positions.realizedTotal()).add(unrealizedTotal());
  }

  /** Σ mark-to-market unrealized over the open positions. */
  public BigDecimal unrealizedTotal() {
    BigDecimal total = BigDecimal.ZERO;
    for (PositionRow pos : positions.listOpen()) {
      BigDecimal mark = lastTick.lastPrice(pos.exchange(), pos.tradingsymbol()).orElse(pos.avgEntryPrice());
      BigDecimal move =
          "BUY".equals(pos.side()) ? mark.subtract(pos.avgEntryPrice()) : pos.avgEntryPrice().subtract(mark);
      total = total.add(move.multiply(BigDecimal.valueOf(pos.qty())));
    }
    return total.setScale(2, RoundingMode.HALF_UP);
  }

  /** Capital usage projected for one order leg (the buying-power input). */
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

  /** Capital usage by class over the open positions. */
  public Map<String, BigDecimal> usageByClass() {
    Map<String, BigDecimal> usage = new LinkedHashMap<>();
    usage.put("equities", BigDecimal.ZERO);
    usage.put("longOptions", BigDecimal.ZERO);
    usage.put("futuresAndShortOptions", BigDecimal.ZERO);
    for (PositionRow pos : positions.listOpen()) {
      InstrumentMeta meta = instruments.meta(pos.exchange(), pos.tradingsymbol());
      BigDecimal amount = usageFor(meta, pos.side(), pos.avgEntryPrice(), pos.qty());
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

  /** Total capital tied up in open positions. */
  public BigDecimal capitalUsed() {
    return usageByClass().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** Free cash = equity − capital used (the buying-power budget). */
  public BigDecimal freeCash() {
    return equity().subtract(capitalUsed());
  }

  /** A non-blocking warning when an order's projected usage exceeds free cash (paper stays paper). */
  public String buyingPowerWarning(BigDecimal projectedUsage) {
    BigDecimal free = freeCash();
    if (projectedUsage.compareTo(free) > 0) {
      return "Projected capital usage "
          + projectedUsage.toPlainString()
          + " exceeds free cash "
          + free.toPlainString()
          + " (paper — not blocked)";
    }
    return null;
  }

  /** The day's P&L = today's realized + current mark-to-market unrealized. */
  public BigDecimal dayPnl() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Kolkata"));
    return positions.realizedOn(today).add(unrealizedTotal());
  }

  /** The /paper account header. */
  public AccountDto account() {
    BigDecimal realized = positions.realizedTotal();
    BigDecimal unrealized = unrealizedTotal();
    BigDecimal startingCapital = account.get().startingCapital();
    BigDecimal equity = startingCapital.add(realized).add(unrealized);
    return new AccountDto(
        startingCapital,
        freeCash(),
        equity,
        realized,
        unrealized,
        dayPnl(),
        positions.openCount(),
        capitalUsed(),
        usageByClass(),
        Map.of("future", futureMarginPct, "shortOption", shortOptionMarginPct));
  }

  /** Owner edit of the starting capital. */
  public void updateStartingCapital(BigDecimal startingCapital) {
    account.updateStartingCapital(startingCapital);
  }
}
