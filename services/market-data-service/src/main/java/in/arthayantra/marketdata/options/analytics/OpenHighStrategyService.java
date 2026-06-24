package in.arthayantra.marketdata.options.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * oipulse "Open &amp; High Strategy" (§strategies/open-high-strategy, Siva #2) — the per-strike
 * Open=High (Call) / Open=Low (Put) premium-reversion scan, with the historical TRIGGER PROBABILITY
 * that distinguishes this page from the single-session OH/OL grader ({@link OpenHighStatsService}).
 *
 * <p>For each (strike, leg) it folds the last-N-session daily premium OHLC ({@link
 * OptionsSnapshotReader#eodSeries}, zero new capture): each session is graded Open=High when {@code
 * |open - high| <= tolerance} (a Call signal) and Open=Low when {@code |open - low| <= tolerance} (a
 * Put signal). The NEWEST session yields the live {@code latestMark}/{@code triggered}; the PRIOR
 * sessions yield the {@code probability} = hits / prior-sessions — the historical odds of the pattern
 * firing for that leg. {@code fallPctFromHigh} is the latest session's gap of close below its high
 * (the LTP-distance reversion gauge oipulse surfaces).
 *
 * <p>Faithful polarity: the page reads Open=High on the Call leg (and Open=Low on the Put leg) as the
 * tradeable reversion signal, so CE legs grade against the OH condition and PE legs against OL. Rows
 * are returned in ascending strike order; the controller windows to the ATM strikes upstream.
 */
@Service
public class OpenHighStrategyService {

  /** Server tolerance (option-premium points) for grading open==high / open==low. */
  private final BigDecimal tolerance;

  public OpenHighStrategyService(@Value("${artha.options.oh-tolerance:1.0}") BigDecimal tolerance) {
    this.tolerance = tolerance;
  }

  /** One (strike, leg) Open=High/Low scan row: latest-session mark + historical trigger probability. */
  public record OpenHighLeg(
      String optionType,
      LocalDate latestDate,
      BigDecimal latestOpen,
      BigDecimal latestHigh,
      BigDecimal latestLow,
      BigDecimal latestClose,
      Long latestOi,
      boolean ohMark,
      boolean olMark,
      boolean triggered,
      BigDecimal fallPctFromHigh,
      int sessions,
      int hits,
      BigDecimal probability) {}

  /** One strike's CE + PE Open=High/Low scan (each leg nullable when that side has no captured day). */
  public record StrikeOpenHigh(BigDecimal strike, OpenHighLeg ce, OpenHighLeg pe) {}

  /**
   * Fold the raw EOD rows (any order) into per-strike CE+PE Open=High/Low scan rows. Strikes are
   * returned ascending; a leg is null when that side has no captured session. Empty list when {@code
   * rows} is empty.
   */
  public List<StrikeOpenHigh> fold(List<OptionsSnapshotReader.OptionEodRow> rows) {
    // (strike) -> leg ("CE"/"PE") -> day rows.
    Map<BigDecimal, Map<String, List<OptionsSnapshotReader.OptionEodRow>>> byStrike =
        new LinkedHashMap<>();
    for (OptionsSnapshotReader.OptionEodRow r : rows) {
      byStrike
          .computeIfAbsent(r.strike(), k -> new LinkedHashMap<>())
          .computeIfAbsent(r.optionType(), k -> new ArrayList<>())
          .add(r);
    }
    List<BigDecimal> strikes = new ArrayList<>(byStrike.keySet());
    strikes.sort(BigDecimal::compareTo);

    List<StrikeOpenHigh> out = new ArrayList<>(strikes.size());
    for (BigDecimal strike : strikes) {
      Map<String, List<OptionsSnapshotReader.OptionEodRow>> legs = byStrike.get(strike);
      out.add(
          new StrikeOpenHigh(
              strike,
              leg("CE", legs.getOrDefault("CE", List.of())),
              leg("PE", legs.getOrDefault("PE", List.of()))));
    }
    return out;
  }

  /**
   * One leg's Open=High (CE) / Open=Low (PE) scan: sort the captured sessions ascending, grade each
   * day's premium OHLC for the pattern, then report the newest session's mark + the prior sessions'
   * hit-rate as the probability. Null when the leg has no captured session.
   */
  private OpenHighLeg leg(String optionType, List<OptionsSnapshotReader.OptionEodRow> raw) {
    if (raw.isEmpty()) {
      return null;
    }
    List<OptionsSnapshotReader.OptionEodRow> asc = new ArrayList<>(raw);
    asc.sort((a, b) -> a.tradeDate().compareTo(b.tradeDate()));

    boolean isCall = "CE".equals(optionType);
    int sessions = asc.size();
    int priorHits = 0;
    for (int i = 0; i < sessions - 1; i++) {
      if (graded(asc.get(i), isCall)) {
        priorHits++;
      }
    }
    int priorSessions = sessions - 1;
    BigDecimal probability =
        priorSessions == 0
            ? null
            : BigDecimal.valueOf(priorHits)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(priorSessions), 2, RoundingMode.HALF_UP);

    OptionsSnapshotReader.OptionEodRow latest = asc.get(sessions - 1);
    boolean oh = isOpenHigh(latest);
    boolean ol = isOpenLow(latest);
    boolean triggered = isCall ? oh : ol;
    return new OpenHighLeg(
        optionType,
        latest.tradeDate(),
        latest.open(),
        latest.high(),
        latest.low(),
        latest.close(),
        latest.oiClose(),
        oh,
        ol,
        triggered,
        fallPctFromHigh(latest),
        sessions,
        priorHits,
        probability);
  }

  /** Whether the day formed the leg's tradeable pattern (OH for a Call, OL for a Put). */
  private boolean graded(OptionsSnapshotReader.OptionEodRow day, boolean isCall) {
    return isCall ? isOpenHigh(day) : isOpenLow(day);
  }

  /** Open=High when |open - high| <= tolerance (the open printed at/near the day high). */
  private boolean isOpenHigh(OptionsSnapshotReader.OptionEodRow day) {
    if (day.open() == null || day.high() == null) {
      return false;
    }
    return day.open().subtract(day.high()).abs().compareTo(tolerance) <= 0;
  }

  /** Open=Low when |open - low| <= tolerance (the open printed at/near the day low). */
  private boolean isOpenLow(OptionsSnapshotReader.OptionEodRow day) {
    if (day.open() == null || day.low() == null) {
      return false;
    }
    return day.open().subtract(day.low()).abs().compareTo(tolerance) <= 0;
  }

  /**
   * The latest session's % gap of close below its high ((high - close) / high * 100), 2dp — the
   * LTP-distance reversion gauge (the smaller the gap, the higher the reversion odds). Null when the
   * high is absent or zero.
   */
  private static BigDecimal fallPctFromHigh(OptionsSnapshotReader.OptionEodRow day) {
    if (day.high() == null || day.close() == null || day.high().signum() == 0) {
      return null;
    }
    return day.high()
        .subtract(day.close())
        .multiply(BigDecimal.valueOf(100))
        .divide(day.high(), 2, RoundingMode.HALF_UP);
  }
}
