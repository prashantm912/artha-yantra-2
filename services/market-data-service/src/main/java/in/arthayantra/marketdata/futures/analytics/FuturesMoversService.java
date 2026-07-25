package in.arthayantra.marketdata.futures.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import in.arthayantra.marketdata.freshness.DataFreshness;
import in.arthayantra.marketdata.options.OiInterpretation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * oipulse futures "Movers" (gainers/losers by price% + OI%) and "Banks" (the queried index's
 * futures term structure with a calendar-spread basis). Both consume the latest-two-bucket window
 * ({@link FuturesSnapshotReader#latestPair}): price/OI deltas come from the bucket diff; price%
 * prefers the day's {@code prevClose} (Stage-G OHLC capture) and falls back to the prior bucket LTP.
 *
 * <p>Note: only index futures (NIFTY 50 / NIFTY BANK monthlies) are captured, so {@code /banks} is
 * a term-structure view of the queried index's contracts — a bank-<em>stock</em> futures grid needs
 * a capture expansion and is a follow-on.
 */
@Service
public class FuturesMoversService {

  public record MoverRow(
      String tradingsymbol,
      @Schema(types = {"number", "null"}) BigDecimal ltp,
      @Schema(types = {"number", "null"}) BigDecimal pricePct,
      @Schema(types = {"number", "null"}) BigDecimal oiPct,
      @Schema(types = {"number", "null"}) BigDecimal dayOpen,
      @Schema(types = {"number", "null"}) BigDecimal dayHigh,
      @Schema(types = {"number", "null"}) BigDecimal dayLow,
      OiInterpretation interpretation) {}

  public record Movers(
      List<MoverRow> gainers,
      List<MoverRow> losers,
      @Schema(types = {"string", "null"}) OffsetDateTime asOf,
      @JsonInclude(JsonInclude.Include.NON_NULL) DataFreshness freshness) {
    public Movers(List<MoverRow> gainers, List<MoverRow> losers, OffsetDateTime asOf) {
      this(gainers, losers, asOf, null);
    }

    /** Returns a copy carrying the freshness envelope (populated at the controller boundary). */
    public Movers withFreshness(DataFreshness f) {
      return new Movers(gainers, losers, asOf, f);
    }
  }

  public record BankRow(
      String tradingsymbol,
      LocalDate expiry,
      @Schema(types = {"number", "null"}) BigDecimal ltp,
      long oi,
      long oiChange,
      @Schema(types = {"number", "null"}) BigDecimal basis,
      OiInterpretation interpretation) {}

  public record Banks(
      List<BankRow> items,
      @Schema(types = {"string", "null"}) OffsetDateTime asOf,
      @JsonInclude(JsonInclude.Include.NON_NULL) DataFreshness freshness) {
    public Banks(List<BankRow> items, OffsetDateTime asOf) {
      this(items, asOf, null);
    }

    /** Returns a copy carrying the freshness envelope (populated at the controller boundary). */
    public Banks withFreshness(DataFreshness f) {
      return new Banks(items, asOf, f);
    }
  }

  private record Pair(FuturesSnapshotReader.FutPoint cur, FuturesSnapshotReader.FutPoint old) {}

  private record Folded(Map<String, Pair> byContract, OffsetDateTime newest) {}

  public Movers movers(List<FuturesSnapshotReader.FutPoint> pair) {
    Folded f = fold(pair);
    if (f == null) {
      return new Movers(List.of(), List.of(), null);
    }
    List<MoverRow> rows = new ArrayList<>();
    for (Pair p : f.byContract().values()) {
      if (p.old() == null) {
        continue;
      }
      long priorOi = p.old().oi() == null ? 0 : p.old().oi();
      long oiDelta = (p.cur().oi() == null ? 0 : p.cur().oi()) - priorOi;
      BigDecimal ltpDelta =
          (p.cur().ltp() == null ? BigDecimal.ZERO : p.cur().ltp())
              .subtract(p.old().ltp() == null ? BigDecimal.ZERO : p.old().ltp());
      BigDecimal oiPct =
          priorOi == 0
              ? null
              : BigDecimal.valueOf(oiDelta)
                  .multiply(BigDecimal.valueOf(100))
                  .divide(BigDecimal.valueOf(priorOi), 2, RoundingMode.HALF_UP);
      rows.add(
          new MoverRow(
              p.cur().tradingsymbol(),
              p.cur().ltp(),
              pricePct(p.cur(), p.old()),
              oiPct,
              p.cur().dayOpen(),
              p.cur().dayHigh(),
              p.cur().dayLow(),
              OiInterpretation.classify(ltpDelta, oiDelta)));
    }
    List<MoverRow> gainers =
        rows.stream()
            .filter(r -> r.pricePct() != null && r.pricePct().signum() >= 0)
            .sorted(Comparator.comparing(MoverRow::pricePct).reversed())
            .toList();
    List<MoverRow> losers =
        rows.stream()
            .filter(r -> r.pricePct() != null && r.pricePct().signum() < 0)
            .sorted(Comparator.comparing(MoverRow::pricePct))
            .toList();
    return new Movers(gainers, losers, f.newest());
  }

  public Banks banks(List<FuturesSnapshotReader.FutPoint> pair) {
    Folded f = fold(pair);
    if (f == null) {
      return new Banks(List.of(), null);
    }
    List<FuturesSnapshotReader.FutPoint> contracts =
        f.byContract().values().stream()
            .map(Pair::cur)
            .filter(c -> c.expiry() != null)
            .sorted(Comparator.comparing(FuturesSnapshotReader.FutPoint::expiry))
            .toList();
    BigDecimal nearestLtp = contracts.isEmpty() ? null : contracts.get(0).ltp();
    List<BankRow> items = new ArrayList<>();
    for (FuturesSnapshotReader.FutPoint c : contracts) {
      Pair p = f.byContract().get(c.tradingsymbol());
      long oiDelta = 0;
      OiInterpretation interp = null;
      if (p.old() != null) {
        long priorOi = p.old().oi() == null ? 0 : p.old().oi();
        oiDelta = (c.oi() == null ? 0 : c.oi()) - priorOi;
        BigDecimal ltpDelta =
            (c.ltp() == null ? BigDecimal.ZERO : c.ltp())
                .subtract(p.old().ltp() == null ? BigDecimal.ZERO : p.old().ltp());
        interp = OiInterpretation.classify(ltpDelta, oiDelta);
      }
      BigDecimal basis =
          c.ltp() == null || nearestLtp == null ? null : c.ltp().subtract(nearestLtp);
      items.add(
          new BankRow(
              c.tradingsymbol(),
              c.expiry(),
              c.ltp(),
              c.oi() == null ? 0 : c.oi(),
              oiDelta,
              basis,
              interp));
    }
    return new Banks(items, f.newest());
  }

  private static BigDecimal pricePct(
      FuturesSnapshotReader.FutPoint cur, FuturesSnapshotReader.FutPoint old) {
    BigDecimal base =
        cur.prevClose() != null && cur.prevClose().signum() != 0
            ? cur.prevClose()
            : (old.ltp() != null && old.ltp().signum() != 0 ? old.ltp() : null);
    if (base == null || cur.ltp() == null) {
      return null;
    }
    return cur.ltp()
        .subtract(base)
        .multiply(BigDecimal.valueOf(100))
        .divide(base, 2, RoundingMode.HALF_UP);
  }

  private static Folded fold(List<FuturesSnapshotReader.FutPoint> pair) {
    OffsetDateTime newest =
        pair.stream()
            .map(FuturesSnapshotReader.FutPoint::bucket)
            .max(Comparator.naturalOrder())
            .orElse(null);
    if (newest == null) {
      return null;
    }
    OffsetDateTime prior =
        pair.stream()
            .map(FuturesSnapshotReader.FutPoint::bucket)
            .filter(b -> b.isBefore(newest))
            .max(Comparator.naturalOrder())
            .orElse(null);
    Map<String, FuturesSnapshotReader.FutPoint> oldAt = new HashMap<>();
    Map<String, Pair> byContract = new HashMap<>();
    for (FuturesSnapshotReader.FutPoint p : pair) {
      if (prior != null && p.bucket().equals(prior)) {
        oldAt.put(p.tradingsymbol(), p);
      }
    }
    for (FuturesSnapshotReader.FutPoint p : pair) {
      if (p.bucket().equals(newest)) {
        byContract.put(p.tradingsymbol(), new Pair(p, oldAt.get(p.tradingsymbol())));
      }
    }
    return new Folded(byContract, newest);
  }
}
