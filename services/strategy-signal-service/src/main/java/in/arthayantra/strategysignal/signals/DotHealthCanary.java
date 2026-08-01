package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Per-DOT input liveness (roadmap F4 v2 / F3 acceptance): the market-data canary watches the data
 * PLANE (ticks/bars/captures); this one watches the GATE'S INPUTS — the rejection-context fields
 * each Connect-the-Dots dot scores from. A dot whose input goes dead doesn't fail loudly: it
 * silently re-caps the composite (the 0.765 ceiling class the 2026-07-02 forensics found months
 * late). Every sweep inspects TODAY's newest rejections; a REQUIRED dot with no live input across
 * the whole window alerts once per day (newly-alive also notes once — that's a fix landing).
 *
 * <p>Liveness has TWO dimensions, because a null-rate probe can only see one of them (G12,
 * 2026-07-29). A dot's input can be dead (absent/sentinel) — or present on every row and FROZEN,
 * carrying one distinct value all session, which re-caps the composite exactly as silently while
 * every alive/dead probe correctly reports it alive. {@code iv_abs_band} is the discovered case and
 * its freeze is legitimate, so the flag is CLASSIFIED per operand (see FreezeClass): a continuous
 * operand frozen across the window PAGES, a daily one only reports, and booleans/enums are not
 * judged at all — one uniform rule would be both noise and blindness at once.
 *
 * <p>And there is a FOURTH state neither dimension can see (G16/T30, findings-README §3.28): the
 * input alive AND moving — distinct values, so the G12 probe is right to call it not-frozen — yet
 * the operand never crosses the dot's threshold, so the dot supports ~0% (or ~100%) of rows and
 * contributes a constant exactly as silently as a dead or frozen input would. {@code breadth} on
 * 2026-07-30 is the discovered case: 0/814 supports off a perfectly healthy input (10 distinct
 * values spanning 23–32) against its {@code > 32} rule, whose session max was EXACTLY 32. The
 * NEAR-MISS probe mechanizes §3.28's EOD instruction ("place the dot's own threshold on the
 * operand's session min/max before reaching for a data explanation"): it flags only when the
 * support rate is strictly one-sided AND the session extremum sits within a small distance of the
 * threshold — an operand far below the line at 0% is a market regime (the 07-31 {@code oi_spurt}
 * conjunct-starved reading), not telemetry, and stays unflagged. Telemetry ONLY: it never pages
 * (surfaced on the endpoint + UI badge), and the threshold itself is doctrine — this class
 * observes the {@code > 32}, it must never move it.
 *
 * <p>Dot registry mirrors the findings-doc §3.7 checks. {@code required-dots} (config) lists the
 * dots expected alive TODAY — grows as fixes land (breadth after #486; iv_rank once the IV-history
 * floor is met; dow stays off the list while un-armed by design). Read surface:
 * {@code GET /api/v1/signal-rejections/dot-health} — the 09:42 agent reads it instead of
 * hand-running the SQL.
 */
@Component
public class DotHealthCanary {

  private static final Logger log = LoggerFactory.getLogger(DotHealthCanary.class);
  private static final LocalTime ARMED_FROM = LocalTime.of(9, 45); // rejections need to accrue
  private static final LocalTime SESSION_END = LocalTime.of(15, 30);
  static final int WINDOW = 40;
  // T17 (2026-07-25): rows blocked at an early rail (time-window / time-of-day / option-side)
  // carry NO context at all, and after ~14:45 the newest rows are ALL that shape — sampling them
  // read every dot dead 4 sessions running (breadth included, while it supported 426/1,100).
  // Fetch a deeper page and keep only context-bearing rows; an all-context-less window is
  // UNINFORMATIVE, never an all-dead verdict.
  static final int FETCH_DEPTH = 200;
  // G12: how many DISTINCT BARS must carry the operand before one distinct value is called frozen.
  // Two bars trivially agree, so a low bar count would report every quiet stretch as a freeze; 8
  // bars is ~24 minutes on the 3m primary. Below it the dot reports not-frozen, never "unknown" —
  // the flag is an assertion, and an un-evidenced assertion must read false.
  static final int MIN_FROZEN_BARS = 8;
  // G16: how many DISTINCT SCORED BARS the near-miss assertion needs — same rationale and value as
  // MIN_FROZEN_BARS (an un-evidenced assertion must read false), a separate name because the two
  // states are separate assertions and may diverge. Below it the dot reads not-near-miss.
  static final int MIN_NEAR_MISS_BARS = 8;

  /**
   * A context-bearing rejection reduced to what the probes read: its diagnostic, its bar, and its
   * option side (the near-miss operand is side-resolved — a CE row's breadth gate tested advances,
   * a PE row's tested declines).
   */
  private record ContextRow(JsonNode diagnostic, OffsetDateTime barTime, String side) {}

  /**
   * How a dot's operand is EXPECTED to behave across a session — this decides whether "one distinct
   * value" is evidence of anything at all. Judging every operand by one rule makes the flag noise:
   * eight unchanged bars is the NORMAL state of a boolean, of a small enum, and of anything sourced
   * from an EOD read (cross-vendor review, 2026-07-29).
   */
  private enum FreezeClass {
    /** Varies intraday. One value across the window is a real defect — PAGES when required. */
    CONTINUOUS,
    /** A once-a-day scalar by construction. Frozen is CORRECT: report it, never page it. */
    DAILY,
    /** Boolean or small enum — repetition carries no information. Not freeze-judged at all. */
    EXEMPT
  }

  /**
   * G16/T30: the near-miss judgment for a dot whose gate tests a FIXED global scalar threshold.
   * Only such dots can carry one — a per-strategy-tunable floor (oi_spurt, iv_abs_band) would make
   * the canary assert against a threshold the row may not have been tested with. {@code operand}
   * returns the value the gate actually tested on THIS row (side-resolved), or null when it cannot
   * be rendered — a null row contributes no evidence, mirroring {@link #text}. {@code threshold} is
   * a doctrine number OBSERVED here, never owned here; {@code epsilon} is the detector's
   * sensitivity ("within a small distance of the line"), not a tuning knob on the rule.
   */
  private record NearMissSpec(
      double threshold, double epsilon, String rule, Function<ContextRow, Double> operand) {}

  /**
   * One dot's probes over a rejection's diagnostic JSON: {@code alive} is the per-row liveness test
   * (OR-folded across the window), {@code operand} renders the value the dot actually scores from so
   * the window can be tested for FREEZE (G12 — see {@link #MIN_FROZEN_BARS}), {@code freeze}
   * says what a freeze would MEAN for that operand, and {@code nearMiss} (nullable — most probes
   * have no fixed global threshold to judge against) carries the G16 fourth-state spec.
   */
  private record Probe(
      String dot, Predicate<JsonNode> alive, Function<JsonNode, String> operand,
      FreezeClass freeze, NearMissSpec nearMiss) {
    Probe(String dot, Predicate<JsonNode> alive, Function<JsonNode, String> operand,
        FreezeClass freeze) {
      this(dot, alive, operand, freeze, null);
    }
  }

  private static final List<Probe> PROBES =
      List.of(
          // advances/declines come off /breadth/live and move all session — a stuck pair is the
          // wedged-read outage class, so this one is CONTINUOUS and pages when required.
          // G16: breadth is also the only probe with a FIXED global scalar rule (ScalperGates
          // .breadth — advances > 32 for CE, declines > 32 for PE, a NIFTY-50-universe doctrine
          // number), so it carries the near-miss spec. Epsilon 3 ≈ 6% of the 50-name universe: the
          // 2026-07-30 discovered case (session max EXACTLY 32, gap 0) sits well inside it, while
          // a decisively one-sided tape (max advances ~20 on a down day, gap 12) stays a market
          // regime, not a telemetry state.
          new Probe("breadth", d -> macroInt(d, "advances") + macroInt(d, "declines") > 0,
              d -> macroInt(d, "advances") + "/" + macroInt(d, "declines"), FreezeClass.CONTINUOUS,
              new NearMissSpec(32, 3, "advances/declines > 32",
                  r -> switch (String.valueOf(r.side())) {
                    case "CE" -> (double) macroInt(r.diagnostic(), "advances");
                    case "PE" -> (double) macroInt(r.diagnostic(), "declines");
                    default -> null; // side unknown — the tested operand cannot be resolved
                  })),
          // ivRank and fiiLongPct are both EOD reads (MarketOiClient.macro asks /iv-history for a
          // daily series and /fii-dii/long-short for the last SETTLED session), so one value per
          // session is their correct behaviour, exactly as for atmIv below.
          new Probe("iv_rank", d -> !d.at("/context/macro/ivRank").isMissingNode()
              && !d.at("/context/macro/ivRank").isNull(), d -> text(d, "/context/macro/ivRank"),
              FreezeClass.DAILY),
          new Probe("dow", d -> !d.at("/context/macro/dowUp").isMissingNode()
              && !d.at("/context/macro/dowUp").isNull(), d -> text(d, "/context/macro/dowUp"),
              FreezeClass.EXEMPT),
          new Probe("fii", d -> !d.at("/context/macro/fiiLongPct").isMissingNode()
              && !d.at("/context/macro/fiiLongPct").isNull(),
              d -> text(d, "/context/macro/fiiLongPct"), FreezeClass.DAILY),
          new Probe("oi_spurt_price", d -> d.at("/context/oi/spurtPricePct").asDouble(0) != 0,
              d -> text(d, "/context/oi/spurtPricePct"), FreezeClass.CONTINUOUS),
          new Probe("vix", d -> !d.at("/context/macro/vixLevel").isMissingNode()
              && !d.at("/context/macro/vixLevel").isNull(), d -> text(d, "/context/macro/vixLevel"),
              FreezeClass.CONTINUOUS),
          // G12: `iv_abs_band` (ConnectTheDotsScorer:210-213, w 0.8) had no probe at all, and it is
          // the dot the frozen dimension was built for. `atmIv` resolves to the last
          // `iv_daily_summary` row (IvRollupJob writes it once at 16:00 IST), so it is non-null all
          // session and reads alive on the old contract while being a per-day STEP FUNCTION (0/180
          // on 2026-07-28, 133/133 on 07-29). DAILY, so it reports and never pages.
          new Probe("iv_abs_band", d -> !d.at("/context/macro/atmIv").isMissingNode()
              && !d.at("/context/macro/atmIv").isNull(), d -> text(d, "/context/macro/atmIv"),
              FreezeClass.DAILY),
          // T13: NEUTRAL is the strategy-side "data missing" sentinel — OiInterpretation.classify is
          // a total function over four real states, so an all-NEUTRAL window means the OI read is
          // broken (the 2026-07-20 outage: 748/748 NEUTRAL, three dots dead, canary green all day).
          // That outage is caught by `alive`; the quadrants are a 4-value enum, so a repeated value
          // is uninformative and they are EXEMPT from the freeze dimension.
          new Probe("futures_oi", d -> quadrantLive(d, "futuresQuadrant"),
              d -> text(d, "/context/oi/futuresQuadrant"), FreezeClass.EXEMPT),
          new Probe("underlying_oi", d -> quadrantLive(d, "underlyingQuadrant"),
              d -> text(d, "/context/oi/underlyingQuadrant"), FreezeClass.EXEMPT));

  private static boolean quadrantLive(JsonNode d, String field) {
    JsonNode q = d.at("/context/oi/" + field);
    return !q.isMissingNode() && !q.isNull() && !"NEUTRAL".equals(q.asText());
  }

  private static int macroInt(JsonNode d, String field) {
    return d.at("/context/macro/" + field).asInt(0);
  }

  /** The operand's rendered value, or null when the field is absent — a null never counts as a value. */
  private static String text(JsonNode d, String pointer) {
    JsonNode n = d.at(pointer);
    return n.isMissingNode() || n.isNull() ? null : n.asText();
  }

  /**
   * The dot's scored {@code supports} flag on this row (from the persisted confluence breakdown —
   * the SAME side-resolved verdict the composite actually used), or null when the row did not score
   * the dot. Joining on the breakdown instead of re-deriving pass/fail keeps the canary from ever
   * disagreeing with the scorer about what "supported" meant.
   */
  private static Boolean scoredSupports(JsonNode diagnostic, String dot) {
    JsonNode dots = diagnostic.at("/confluence/dots");
    if (!dots.isArray()) {
      return null;
    }
    for (JsonNode n : dots) {
      if (dot.equals(n.path("dot").asText())) {
        return n.path("supports").asBoolean(false);
      }
    }
    return null;
  }

  /**
   * G16/T30: the near-miss verdict for a spec-carrying probe over EVERY context-bearing row in the
   * scan (same depth as the G12 freeze pass, same reasoning). Returns the detail note when the dot
   * is live-but-never-crossing SO FAR TODAY, else null. Flags iff, over the rows that scored the
   * dot: (1) at least {@link #MIN_NEAR_MISS_BARS} distinct bars carry a resolvable operand — below
   * that the assertion is un-evidenced and must read false; (2) supports are STRICTLY one-sided —
   * a single crossing proves the dot CAN discriminate, so any mix reads false (this is also what
   * keeps a fan-out spike from counterfeiting the state); and (3) the session extremum sits within
   * {@code epsilon} of the threshold — max just under the line at ~0% (the 2026-07-30 breadth
   * case), or min just over it at ~100% (the mirror: always crossing, never discriminating). An
   * operand far from the line at 0% is a REGIME (the 07-31 oi_spurt conjunct-starved reading), not
   * telemetry, and deliberately does not flag.
   */
  private static String nearMiss(Probe p, List<ContextRow> freezeRows) {
    NearMissSpec spec = p.nearMiss();
    int supported = 0;
    int opposed = 0;
    double max = Double.NEGATIVE_INFINITY;
    double min = Double.POSITIVE_INFINITY;
    Set<OffsetDateTime> scoredBars = new LinkedHashSet<>();
    for (ContextRow r : freezeRows) {
      Boolean s = scoredSupports(r.diagnostic(), p.dot());
      Double v = s == null ? null : spec.operand().apply(r);
      if (v == null) {
        continue; // not scored on this row, or the tested operand is unresolvable — no evidence
      }
      scoredBars.add(r.barTime());
      if (s) {
        supported++;
      } else {
        opposed++;
      }
      max = Math.max(max, v);
      min = Math.min(min, v);
    }
    int rows = supported + opposed;
    if (scoredBars.size() < MIN_NEAR_MISS_BARS || (supported > 0 && opposed > 0)) {
      return null;
    }
    // Strictly one-sided from here, so the extremum's side of the line is determined: all-opposed
    // puts every tested value at-or-under the threshold (gap >= 0), all-supported strictly over it.
    double gap = supported == 0 ? spec.threshold() - max : min - spec.threshold();
    if (gap > spec.epsilon()) {
      return null;
    }
    return supported == 0
        ? " · NEAR-MISS — live and moving, yet supported 0/" + rows + " scored rows so far today;"
            + " session max " + trim(max) + " never crossed " + spec.rule() + " (gap " + trim(gap)
            + " <= " + trim(spec.epsilon()) + ") — §3.28's third state: not dead, not frozen,"
            + " never crossing"
        : " · NEAR-MISS — live and moving, yet supported " + rows + "/" + rows + " scored rows so"
            + " far today; session min " + trim(min) + " never dropped to " + spec.rule()
            + " (margin " + trim(gap) + " <= " + trim(spec.epsilon()) + ") — always crossing,"
            + " never discriminating";
  }

  /** Renders a double without a spurious {@code .0} (breadth counts are integers on the wire). */
  private static String trim(double v) {
    return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
  }

  /**
   * One dot's current verdict. {@code frozen} is the G12 dimension: the input is present on every row
   * (so {@code alive} is true) but carries exactly ONE distinct value across the window — a state no
   * null-rate probe can see. It never pages on its own; {@code detail} says whether the freeze is
   * by design. {@code neverCrossing} is the G16 fourth state: input alive AND moving (so neither
   * the liveness nor the G12 probe can see it) yet strictly one-sided on supports with the session
   * extremum within a small distance of the dot's threshold — the near-miss signature. Telemetry
   * only; it never pages.
   */
  public record DotState(
      String dot, boolean alive, boolean required, boolean frozen, boolean neverCrossing,
      String detail) {}

  /**
   * The endpoint payload. {@code rowsInspected} counts the CONTEXT-BEARING rows the probes actually
   * read (T17 — the old any-row count let an all-context-less tail read as an all-dead verdict);
   * {@code rowsScanned} is the raw page depth for transparency.
   */
  public record DotHealth(
      String asOf, boolean session, int rowsScanned, int rowsInspected, List<DotState> dots) {}

  /**
   * In-process alert event — the notifier module listens (same direction as {@link SignalEmitted};
   * signals must not import notifier, that is a module cycle).
   */
  public record DotInputAlert(String title, String message) {}

  // Every probe fed by MarketOiClient.oi() is exempt from paging on a monthly index-expiry day: that
  // method SKIPS the whole OI block then (S24 — chain OI is corrupted by the expiring series' unwind)
  // and returns inert defaults, so a dead window is by-design, not an outage.
  //
  // The inert Oi (MarketOiClient:292-294) NEUTRALs both quadrants AND nulls the spurt magnitudes, so
  // `oi_spurt_price` belongs here too — it was missing until 2026-07-28, when a live check read it as
  // a plain "input dead" outage on an expiry day while its two siblings correctly read by-design.
  // Only these three probes read /context/oi; the rest read /context/macro and are unaffected.
  private static final Set<String> S24_SUPPRESSED_DOTS =
      Set.of("futures_oi", "underlying_oi", "oi_spurt_price");

  private final SignalRejectionRepository rejections;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final MarketCalendar calendar = MarketCalendar.nse();
  private final MarketCalendar bseCalendar = MarketCalendar.bse();
  private final Set<String> required;
  private final Map<String, LocalDate> alertedDeadOn = new ConcurrentHashMap<>();
  private final Map<String, LocalDate> alertedFrozenOn = new ConcurrentHashMap<>();
  private final Set<String> deadNow = ConcurrentHashMap.newKeySet();

  /** Dot → how its operand is expected to behave, so {@link #sweep()} can page only on CONTINUOUS. */
  private static final Map<String, FreezeClass> FREEZE_CLASS =
      PROBES.stream().collect(java.util.stream.Collectors.toMap(Probe::dot, Probe::freeze));

  /** Wires the rejection source, the in-process event bus and the required-alive dot list. */
  public DotHealthCanary(
      SignalRejectionRepository rejections,
      ApplicationEventPublisher events,
      Clock clock,
      @Value("${artha.canary.required-dots:breadth,futures_oi,underlying_oi}") String requiredDots) {
    this.rejections = rejections;
    this.events = events;
    this.clock = clock;
    this.required = Set.of(requiredDots.isBlank() ? new String[0] : requiredDots.split("\\s*,\\s*"));
  }

  /** One evaluation over today's newest CONTEXT-BEARING rejections (T17; controller calls per GET). */
  public DotHealth evaluate() {
    ZonedDateTime now = clock.instant().atZone(Ist.ZONE);
    boolean session = inSession(now);
    List<SignalRejectionRepository.RejectionRow> scanned =
        rejections.list(
            null, null, null, null,
            now.toLocalDate().atTime(LocalTime.of(9, 15)).atZone(Ist.ZONE).toOffsetDateTime(),
            null, FETCH_DEPTH, 0);
    List<ContextRow> contextRows = new ArrayList<>(WINDOW);
    // G12 / cross-vendor review 2026-07-29: the FREEZE pass reads EVERY context-bearing row in the
    // scan, not the 40-row liveness window. The two need different depths, and the 40-row cap made
    // the freeze flag inert on a third of sessions. Distinct bars per session, 2026-07-20..29:
    //
    //   old (first 40 context-bearing rows):  18 /  4 / 18 / 14 /  4 / 18 /  7 / 17
    //   new (all context-bearing in the scan): 22 /  7 / 25 / 20 / 10 / 20 / 11 / 21
    //
    // The rows are ALREADY FETCHED, so this costs no extra query — it just stops discarding them.
    // ⚠️ It does NOT rescue every session: 2026-07-21 only reaches 7 bars, still under
    // MIN_FROZEN_BARS, because FETCH_DEPTH scans 200 RAW rows and a thin session leaves few of them
    // context-bearing (76 that day). The probe therefore ABSTAINS on a thin session rather than
    // asserting a freeze off a handful of bars — the safe direction, and consistent with
    // MIN_FROZEN_BARS' own rule that an un-evidenced assertion must read false. Raising FETCH_DEPTH
    // would close it but doubles a 5-minutely read that MonitorSchedulingConfig deliberately bounds,
    // so that is an owner call, not a silent widening.
    List<ContextRow> freezeRows = new ArrayList<>(FETCH_DEPTH);
    for (SignalRejectionRepository.RejectionRow row : scanned) {
      JsonNode d = row.diagnostic();
      if (d != null && !d.at("/context").isMissingNode() && d.at("/context").size() > 0) {
        ContextRow ctx = new ContextRow(d, row.barTime(), row.side());
        freezeRows.add(ctx);
        if (contextRows.size() < WINDOW) {
          contextRows.add(ctx);
        }
      }
    }
    // Suppression is PER OI ROOT, so it may only stand down the probes when EVERY row in the window
    // came from a suppressed root. MarketOiClient.oi() keys on the row's own underlying
    // (ScalperCalendars.forUnderlying — BSE Thursday monthly for SENSEX, NSE monthly for the rest),
    // so on an NSE-only expiry day a SENSEX-rooted read is NOT suppressed and a dead OI dot there is
    // a genuine outage. Treating either calendar's expiry as a blanket exemption would silence it.
    // One date for the whole sweep — re-reading the clock per row could straddle IST midnight.
    LocalDate today = now.toLocalDate();
    boolean allRowsSuppressed =
        !contextRows.isEmpty()
            && contextRows.stream().allMatch(r -> rootSuppressedToday(r.diagnostic(), today));
    List<DotState> dots = new ArrayList<>(PROBES.size());
    for (Probe p : PROBES) {
      boolean alive = false;
      for (ContextRow r : contextRows) {
        if (p.alive().test(r.diagnostic())) {
          alive = true;
          break;
        }
      }
      // `required` means "expected alive TODAY" (class javadoc). On a monthly index-expiry day the
      // OI reads are S24-suppressed by design, so those dots are NOT expected alive — dropping the
      // flag here keeps every consumer (paging, /status count, UI badge) agreeing with the
      // no-outage decision instead of each needing its own exemption.
      boolean suppressedToday = allRowsSuppressed && S24_SUPPRESSED_DOTS.contains(p.dot());
      // G12: distinct operand values over the FREEZE window, counted PER BAR and never per row. One
      // 3m bar fans out across many scalpers, so a row count would read "one distinct value" off a
      // single bar's worth of identical macro context and call a perfectly live input frozen. (The
      // same fan-out inflation that made the champion book's best session look like 24 observations
      // when it was ~6.) Only bars that CARRY the operand count, so a partially-null field is judged
      // on the bars that have it.
      Set<String> distinct = new LinkedHashSet<>();
      Set<OffsetDateTime> carryingBars = new LinkedHashSet<>();
      for (ContextRow r : freezeRows) {
        String value = p.operand().apply(r.diagnostic());
        if (value != null) {
          carryingBars.add(r.barTime());
          distinct.add(value);
        }
      }
      int carrying = carryingBars.size();
      // EXEMPT operands are booleans and small enums: repetition is their normal state and carries
      // no information, so they are never freeze-judged. An S24-suppressed read is not being
      // produced at all today, so its single inert value is by-design inertness rather than a
      // freeze — flagging it would light the badge every expiry day for a state the suppression
      // branch already explains.
      boolean frozen =
          p.freeze() != FreezeClass.EXEMPT
              && carrying >= MIN_FROZEN_BARS
              && distinct.size() == 1
              && !suppressedToday;
      String detail;
      if (alive) {
        detail = "input live in the last " + contextRows.size() + " context-bearing rejections";
      } else if (contextRows.isEmpty()) {
        detail =
            scanned.isEmpty()
                ? "no rejections yet today"
                : "UNINFORMATIVE — " + scanned.size() + " rejections scanned, none carry context"
                    + " (early-rail blocks only)";
      } else if (suppressedToday) {
        // Deliberately says "inert", not "NEUTRAL": the quadrants degrade to NEUTRAL but the spurt
        // magnitudes degrade to NULL, and both come off the same skipped read.
        detail =
            "inert by design — monthly index-expiry day, OI reads S24-suppressed (not an outage)";
      } else {
        detail = "input dead across " + contextRows.size() + " context-bearing rejections";
      }
      // G16 fourth state: only judged when the dot is alive, MOVING (distinct values — a frozen or
      // freeze-abstained single value is the G12 state's territory, never double-reported here),
      // carries a fixed-threshold spec, and is not S24-suppressed today (an inert read is by-design
      // inertness, not a near miss).
      String nearMissNote =
          p.nearMiss() != null && alive && !frozen && distinct.size() > 1 && !suppressedToday
              ? nearMiss(p, freezeRows)
              : null;
      boolean neverCrossing = nearMissNote != null;
      // APPENDED, never substituted: a dot can be dead AND frozen (an all-NEUTRAL quadrant window
      // is both), and the liveness half is the half that pages — it must not be overwritten.
      if (frozen) {
        detail +=
            p.freeze() == FreezeClass.DAILY
                // Established by code read 2026-07-29, not inferred: `atmIv` comes from
                // MarketOiClient.macro() -> GET /api/v1/market/options/iv-history -> `currentIv`,
                // which IvAnalyticsService resolves to the LAST `iv_daily_summary` row — a table
                // IvRollupJob writes once per day at 16:00 IST. Intraday it is the previous
                // session's EOD scalar and cannot move: one value per session is CORRECT here, and
                // the defect the freeze exposes belongs to the dot, not to the feed. `iv_rank` and
                // `fii` are the same shape, off their own EOD reads.
                ? " · frozen BY DESIGN — one value (" + distinct.iterator().next() + ") across "
                    + carrying + " bars (EOD daily operand — correct, not an outage)"
                : " · FROZEN — ONE distinct value (" + distinct.iterator().next() + ") across "
                    + carrying + " bars; a null-rate probe cannot see this";
      }
      if (neverCrossing) {
        detail += nearMissNote;
      }
      dots.add(
          new DotState(
              p.dot(), alive, required.contains(p.dot()) && !suppressedToday, frozen,
              neverCrossing, detail));
    }
    return new DotHealth(
        now.toOffsetDateTime().toString(), session, scanned.size(), contextRows.size(),
        List.copyOf(dots));
  }

  /** The 5-min alerting sweep; only REQUIRED dots page, once per day per transition. */
  @Scheduled(fixedDelay = 300_000, initialDelay = 180_000, scheduler = "monitorTaskScheduler")
  public void sweep() {
    try {
      ZonedDateTime now = clock.instant().atZone(Ist.ZONE);
      if (!inSession(now) || now.toLocalTime().isBefore(ARMED_FROM)) {
        return;
      }
      DotHealth health = evaluate();
      if (health.rowsInspected() == 0) {
        return; // engine-silence is the data-plane canary's problem, not a dot verdict
      }
      LocalDate today = now.toLocalDate();
      Map<String, DotState> byDot = new LinkedHashMap<>();
      health.dots().forEach(s -> byDot.put(s.dot(), s));
      for (String dot : required) {
        DotState state = byDot.get(dot);
        if (state == null) {
          continue;
        }
        // `required` on the STATE already means "expected alive today" — evaluate() drops it for an
        // S24-suppressed dot. Reading it here instead of recomputing the expiry test keeps paging
        // and the endpoint on one decision (they disagreed once, per-root, before this).
        if (!state.required()) {
          continue;
        }
        if (!state.alive()) {
          if (!today.equals(alertedDeadOn.get(dot))) {
            alertedDeadOn.put(dot, today);
            log.error("dot canary: required dot '{}' input DEAD — {}", dot, state.detail());
            send("ArthaYantra dot canary: " + dot + " DEAD",
                "required dot '" + dot + "' has no live input — " + state.detail()
                    + " (composite silently re-capped)");
          }
          deadNow.add(dot);
        } else if (deadNow.remove(dot)) {
          send("ArthaYantra dot canary recovered", "dot '" + dot + "' input is live again");
        }
        // G12 / cross-vendor review 2026-07-29: a CONTINUOUS operand stuck on one value for the
        // whole window is an outage the `alive` probe cannot see — the field is populated, so the
        // dot reads live while contributing a constant. It must page, or the freeze dimension only
        // ever reaches someone who happens to open the rejections page. DAILY operands are exempt
        // by construction (their freeze is correct) and EXEMPT ones are never flagged at all, so
        // this cannot fire on `iv_abs_band`, `iv_rank` or `fii`.
        // ⚠️ `state.alive()` is REQUIRED here, not redundant with the branch above. A dead operand
        // can also read frozen: breadth's sentinel is 0/0, which is dead by the `alive` test but
        // still renders a non-null operand, so eight bars of outage make it BOTH. Without this the
        // sweep pages DEAD and then immediately pages FROZEN with a message claiming the input
        // "reads live" — two alerts for one outage, the second contradicting the first.
        if (state.frozen() && state.alive() && FREEZE_CLASS.get(dot) == FreezeClass.CONTINUOUS
            && !today.equals(alertedFrozenOn.get(dot))) {
          alertedFrozenOn.put(dot, today);
          log.error("dot canary: required dot '{}' input FROZEN — {}", dot, state.detail());
          send("ArthaYantra dot canary: " + dot + " FROZEN",
              "required dot '" + dot + "' reads live but has not changed value — "
                  + state.detail() + " (composite silently re-capped)");
        }
      }
    } catch (RuntimeException e) {
      log.warn("dot canary sweep failed: {}", e.toString());
    }
  }

  private void send(String title, String message) {
    try {
      events.publishEvent(new DotInputAlert(title, message));
    } catch (RuntimeException e) {
      log.warn("dot canary alert failed: {}", e.getMessage());
    }
  }

  /**
   * Is THIS row's OI root S24-suppressed today? Mirrors {@code ScalperCalendars.forUnderlying} (BSE
   * Thursday monthly for a SENSEX-rooted read, NSE monthly for everything else) — that class is
   * package-private in {@code scalper} and signals must not reach into it, so the one-line root rule
   * is restated rather than imported. A row with no {@code /context/underlying} falls to the NSE
   * calendar, which on a BSE-only expiry day reads NOT-suppressed and therefore pages: fail-loud is
   * the right default for a canary.
   */
  private boolean rootSuppressedToday(JsonNode diagnostic, LocalDate today) {
    String underlying = diagnostic.at("/context/underlying").asText("");
    MarketCalendar rootCalendar =
        underlying.toUpperCase(Locale.ROOT).contains("SENSEX") ? bseCalendar : calendar;
    try {
      return rootCalendar.isMonthlyIndexExpiryDay(today);
    } catch (IllegalArgumentException uncoveredYear) {
      return false; // the calendar cliff has its own canary
    }
  }

  private boolean inSession(ZonedDateTime now) {
    try {
      return calendar.isOpen(now.toInstant()) && now.toLocalTime().isBefore(SESSION_END);
    } catch (IllegalArgumentException uncoveredYear) {
      return false; // the calendar cliff has its own canary
    }
  }
}
