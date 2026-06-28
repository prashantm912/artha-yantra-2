package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.paper.PaperPositionRepository.SubAccountTally;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.WinLoss;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Siva's 5-sub-account scalper discipline (master plan §12.7 / cheat sheet §0A) as an enforced ENTRY
 * rail. Two daily caps are derived from the day's CLOSED paper trades (read-time, no per-trade
 * feedback loop), consulted ONLY on a scalper ENTRY (alongside the global {@link RiskService} gate):
 *
 * <ul>
 *   <li><b>First-loss freeze (5 accounts):</b> capital is split across 5 logical sub-accounts; a
 *       losing trade freezes the sub-account that took it for the IST day. Once all 5 are frozen,
 *       no fresh scalper entry is allowed. When trades carry a {@code subaccount_idx} (E10 ledger)
 *       the freeze is PER-ACCOUNT — a loss freezes the specific account that took it. When NO trade
 *       carries an idx (legacy / non-scalper rows only — the NULL-idx fallback), it degrades to the
 *       day-granularity loss COUNT against 5 accounts, preserving the prior semantics exactly.
 *   <li><b>5-wins/day cap:</b> after 5 winning trades the day's gains are banked and fresh scalper
 *       entries stop (overtrading is the killer — §2.14). This aggregate cap spans all closed trades.
 * </ul>
 *
 * <p>EXITS are never gated by this — only fresh entries. State is the {@code paper_positions} ledger,
 * so it survives a restart and is consistent with the live account; nothing is held in memory.
 */
@Component
public class ScalperAccountModel {

  /** Logical sub-accounts: a losing trade freezes one; all frozen ⇒ no fresh entry. */
  static final int ACCOUNTS = 5;

  /** Daily winning-trade cap: bank the gains and stop after this many wins. */
  static final int MAX_WINS_PER_DAY = 5;

  private static final Logger log = LoggerFactory.getLogger(ScalperAccountModel.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final PaperPositionRepository positions;
  private final Clock clock;

  /** Wires the closed-trade ledger. */
  public ScalperAccountModel(PaperPositionRepository positions, Clock clock) {
    this.positions = positions;
    this.clock = clock;
  }

  /** False once all sub-accounts are frozen (per-account first-loss, or the legacy count) OR 5 wins
   * have banked the day. */
  public boolean scalperEntryAllowed() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    WinLoss wl = positions.winLossOn(today);
    if (wl.wins() >= MAX_WINS_PER_DAY) {
      log.info("scalper entries paused — {} wins banked today (cap {})", wl.wins(), MAX_WINS_PER_DAY);
      return false;
    }
    List<SubAccountTally> tallies = positions.subAccountTalliesOn(today);
    if (tallies.isEmpty()) {
      // NULL-idx fallback: no trade carries a sub-account today (legacy / non-scalper only) → the
      // prior day-granularity loss COUNT decides, so old rows + the aggregate caps behave identically.
      if (wl.losses() >= ACCOUNTS) {
        log.info("scalper entries paused — {} losses froze all {} sub-accounts", wl.losses(), ACCOUNTS);
        return false;
      }
      return true;
    }
    // Per-account first-loss freeze: an account is frozen for the day once a trade charged to it
    // closed at a loss (≤ 0). All 5 frozen ⇒ no fresh entry.
    long frozen = tallies.stream().filter(t -> t.losses() > 0).count();
    if (frozen >= ACCOUNTS) {
      log.info("scalper entries paused — all {} sub-accounts frozen on a losing trade", ACCOUNTS);
      return false;
    }
    return true;
  }

  /**
   * The sub-account (1..5) to charge a fresh scalper entry to: the NON-frozen account with the fewest
   * trades today (round-robin load balancing). An account is frozen once a trade charged to it closed
   * at a loss. Falls back to account 1 if every account is frozen — the entry gate
   * ({@link #scalperEntryAllowed}) normally blocks first, but a manually-TAKEN signal can bypass it.
   */
  public int nextFreeAccount() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    java.util.Map<Integer, Integer> tradeCount = new java.util.HashMap<>();
    java.util.Set<Integer> frozen = new java.util.HashSet<>();
    for (SubAccountTally t : positions.subAccountTalliesOn(today)) {
      tradeCount.put(t.idx(), t.wins() + t.losses());
      if (t.losses() > 0) {
        frozen.add(t.idx());
      }
    }
    int best = 1;
    int bestCount = Integer.MAX_VALUE;
    for (int idx = 1; idx <= ACCOUNTS; idx++) {
      if (frozen.contains(idx)) {
        continue;
      }
      int count = tradeCount.getOrDefault(idx, 0);
      if (count < bestCount) {
        bestCount = count;
        best = idx;
      }
    }
    return best;
  }
}
