package in.arthayantra.strategysignal.insights;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The resolved {@code artha.insights.*} config subtree (INT design §2.5 / §3.2). All priority
 * weights, bands, trust cap, per-family bands/half-lives, and suppression/risk thresholds live here
 * and are hashed into every insight row's {@code config_hash} — changing a weight is visible in every
 * row it produced (§3.2 last line). Constructor-bound record with in-place defaults so a stack that
 * sets none still runs.
 *
 * <p>Priority and generator knobs remain YAML-only; I4 delivery flags are env-tunable and fail-closed.
 */
@ConfigurationProperties("artha.insights")
public record InsightProperties(
    Priority priority,
    Risk risk,
    Retention retention,
    Context context,
    Rejection rejection,
    Hygiene hygiene,
    Expiry expiry,
    Quality quality,
    SellDecision sellDecision,
    Delivery delivery) {

  /** Fills the whole tree with defaults when a section (or the root) is absent. */
  public InsightProperties {
    priority = priority == null ? Priority.defaults() : priority;
    risk = risk == null ? Risk.defaults() : risk;
    retention = retention == null ? Retention.defaults() : retention;
    context = context == null ? Context.defaults() : context;
    rejection = rejection == null ? Rejection.defaults() : rejection;
    hygiene = hygiene == null ? Hygiene.defaults() : hygiene;
    expiry = expiry == null ? Expiry.defaults() : expiry;
    quality = quality == null ? Quality.defaults() : quality;
    sellDecision = sellDecision == null ? SellDecision.defaults() : sellDecision;
    delivery = delivery == null ? Delivery.defaults() : delivery;
  }

  /** Signal-priority weights, bands, trust cap, and per-family band/half-life/TTL (§3.2). */
  public record Priority(
      Weights weights,
      Bands bands,
      @DefaultValue("0.79") BigDecimal degradedTrustCap,
      @DefaultValue("20") int closedTradesFloor,
      Map<String, BigDecimal> edgeBand,
      Map<String, Long> halfLifeSeconds,
      Map<String, Long> signalTtlMinutes) {

    public Priority {
      weights = weights == null ? Weights.defaults() : weights;
      bands = bands == null ? Bands.defaults() : bands;
      degradedTrustCap = degradedTrustCap == null ? new BigDecimal("0.79") : degradedTrustCap;
      edgeBand = edgeBand == null || edgeBand.isEmpty() ? DEFAULT_EDGE_BAND : edgeBand;
      halfLifeSeconds =
          halfLifeSeconds == null || halfLifeSeconds.isEmpty() ? DEFAULT_HALF_LIFE : halfLifeSeconds;
      signalTtlMinutes =
          signalTtlMinutes == null || signalTtlMinutes.isEmpty() ? DEFAULT_TTL : signalTtlMinutes;
    }

    private static final Map<String, BigDecimal> DEFAULT_EDGE_BAND =
        Map.of("scalper", new BigDecimal("0.10"), "swing", new BigDecimal("0.10"));
    private static final Map<String, Long> DEFAULT_HALF_LIFE =
        Map.of("scalper", 600L, "swing", 172_800L); // 10 min / 2 sessions
    private static final Map<String, Long> DEFAULT_TTL =
        Map.of("scalper", 30L, "swing", 2_880L); // 30 min / 2 days

    static Priority defaults() {
      return new Priority(Weights.defaults(), Bands.defaults(), new BigDecimal("0.79"), 20, null, null, null);
    }

    /** The edge-normalization band width for a family (§3.2 edge row); default 0.10. */
    public BigDecimal edgeBandFor(String family) {
      return edgeBand.getOrDefault(family, new BigDecimal("0.10"));
    }

    /** The freshness half-life (seconds) for a family (§3.2 freshness row). */
    public long halfLifeFor(String family) {
      return halfLifeSeconds.getOrDefault(family, 600L);
    }

    /** The signal-scoped insight TTL (minutes) for a family. */
    public long ttlMinutesFor(String family) {
      return signalTtlMinutes.getOrDefault(family, 30L);
    }
  }

  /** The five component weights (sum 1.00, §3.2). */
  public record Weights(
      @DefaultValue("0.30") BigDecimal edge,
      @DefaultValue("0.20") BigDecimal context,
      @DefaultValue("0.20") BigDecimal track,
      @DefaultValue("0.15") BigDecimal risk,
      @DefaultValue("0.15") BigDecimal freshness) {
    static Weights defaults() {
      return new Weights(
          new BigDecimal("0.30"), new BigDecimal("0.20"), new BigDecimal("0.20"),
          new BigDecimal("0.15"), new BigDecimal("0.15"));
    }
  }

  /** Band thresholds A≥a, B≥b, C≥c, else D (§3.2). */
  public record Bands(
      @DefaultValue("80") int a, @DefaultValue("60") int b, @DefaultValue("40") int c) {
    static Bands defaults() {
      return new Bands(80, 60, 40);
    }
  }

  /** RISK_HEAT thresholds (§3.2 risk row / §2.5 cooldown). */
  public record Risk(
      @DefaultValue("70") BigDecimal warnPct,
      @DefaultValue("90") BigDecimal criticalPct,
      @DefaultValue("0.80") BigDecimal capWarnFraction,
      @DefaultValue("2") int concentrationMin,
      @DefaultValue("15") int cooldownMinutes) {
    static Risk defaults() {
      return new Risk(new BigDecimal("70"), new BigDecimal("90"), new BigDecimal("0.80"), 2, 15);
    }
  }

  /** Suppressed-INFO prune horizon (§2.5 hygiene); the prune job itself lands in a later wave. */
  public record Retention(@DefaultValue("90") int pruneDays) {
    static Retention defaults() {
      return new Retention(90);
    }
  }

  /**
   * CONTEXT_SHIFT + MARKET_STRUCTURE thresholds (§6.2). Each crossing reads the digest's own
   * baseline-relative delta ("since open" / "vs prior") — the layer never persists its own baseline.
   */
  public record Context(
      List<String> underlyings,
      @DefaultValue("0.15") BigDecimal pcrDelta,
      @DefaultValue("50") BigDecimal maxPainDrift,
      @DefaultValue("20") BigDecimal straddlePct,
      @DefaultValue("2") int activeStrikeChange,
      @DefaultValue("0.5") BigDecimal gapOpenPct,
      @DefaultValue("45") int shiftCooldownMinutes,
      @DefaultValue("60") int structureCooldownMinutes) {

    /**
     * ⚠️ {@code NIFTY 50}, not {@code NIFTY} (chip task_ffefe53e). The canonical instrument key is
     * the one {@code marketdata.instruments} is keyed by, and the options-digest endpoint 404s on
     * anything else — measured live 2026-08-19: {@code name=NIFTY} → 404, {@code name=NIFTY 50} →
     * 200. Because {@link ContextClient} fail-softs a 404 to an empty Optional, the bare name was a
     * SILENT miss: {@code strategy.insights} holds four underlying-scoped CONTEXT_SHIFT rows in the
     * entire history of the feature and every one is {@code BSE:SENSEX}.
     */
    private static final List<String> DEFAULT_UNDERLYINGS = List.of("NIFTY 50", "SENSEX");

    public Context {
      underlyings = underlyings == null || underlyings.isEmpty() ? DEFAULT_UNDERLYINGS : underlyings;
      pcrDelta = pcrDelta == null ? new BigDecimal("0.15") : pcrDelta;
      maxPainDrift = maxPainDrift == null ? new BigDecimal("50") : maxPainDrift;
      straddlePct = straddlePct == null ? new BigDecimal("20") : straddlePct;
      gapOpenPct = gapOpenPct == null ? new BigDecimal("0.5") : gapOpenPct;
    }

    static Context defaults() {
      return new Context(DEFAULT_UNDERLYINGS, new BigDecimal("0.15"), new BigDecimal("50"),
          new BigDecimal("20"), 2, new BigDecimal("0.5"), 45, 60);
    }
  }

  /** REJECTION_NEARMISS + REJECTION_RAIL_TREND thresholds (§4.2). */
  public record Rejection(
      @DefaultValue("120") int nearMissWindowMinutes,
      @DefaultValue("5") int nearMissTopN,
      @DefaultValue("0.05") BigDecimal nearMissMaxCloseness,
      @DefaultValue("45") int nearMissCooldownMinutes,
      @DefaultValue("5") int railTrendSessions,
      @DefaultValue("2.0") BigDecimal railTrendRatio,
      @DefaultValue("5") int railTrendMinCount) {

    public Rejection {
      nearMissMaxCloseness =
          nearMissMaxCloseness == null ? new BigDecimal("0.05") : nearMissMaxCloseness;
      railTrendRatio = railTrendRatio == null ? new BigDecimal("2.0") : railTrendRatio;
    }

    static Rejection defaults() {
      return new Rejection(120, 5, new BigDecimal("0.05"), 45, 5, new BigDecimal("2.0"), 5);
    }
  }

  /** HYGIENE nudge thresholds (§2.2). */
  public record Hygiene(@DefaultValue("7") int windowDays, @DefaultValue("1") int minUnrated) {
    static Hygiene defaults() {
      return new Hygiene(7, 1);
    }
  }

  /** EXPIRY_EVENT window (§2.2) — T-1 = 1 day ahead. */
  public record Expiry(@DefaultValue("1") int daysAhead) {
    static Expiry defaults() {
      return new Expiry(1);
    }
  }

  /** QUALITY_REPORT window + targets (§10.2). */
  public record Quality(
      @DefaultValue("7") int windowDays,
      @DefaultValue("0.60") BigDecimal actRateTarget,
      @DefaultValue("0.40") BigDecimal dismissRateThreshold,
      @DefaultValue("5") int topSuppressedKeys) {

    public Quality {
      actRateTarget = actRateTarget == null ? new BigDecimal("0.60") : actRateTarget;
      dismissRateThreshold =
          dismissRateThreshold == null ? new BigDecimal("0.40") : dismissRateThreshold;
    }

    static Quality defaults() {
      return new Quality(7, new BigDecimal("0.60"), new BigDecimal("0.40"), 5);
    }
  }

  /** SELL_DECISION escalation window (§5.3) — sessions un-acknowledged before NOTICE escalates to WARN. */
  public record SellDecision(@DefaultValue("1") int escalateSessions) {
    static SellDecision defaults() {
      return new SellDecision(1);
    }
  }

  /**
   * I4 delivery flags, the common severity floor for phone channels, and the CONTEXT_SHIFT phone
   * budget.
   *
   * <p>⚠️ <b>Why {@code contextShiftDailyPhoneCap} exists, and why it is armed BEFORE it is needed.</b>
   * CONTEXT_SHIFT candidates are deduped per {@code (scope, metric)} on a 45-minute cooldown
   * ({@code shiftCooldownMinutes}) and the sweep runs every 15 minutes across the session, so ONE key
   * can legitimately re-fire ~8 times a day. There are nine keys today (four option metrics x two
   * underlyings, plus {@code market:GAP_OPEN}), which bounds the uncapped worst case near 77 phone
   * notifications in a session.
   *
   * <p>None of that is visible right now, and that is the trap: every options digest currently reads
   * {@code dataTrust: DEGRADED} ("ATM IV rank over 38 sessions (< 60-session floor)", measured live
   * 2026-08-20), and {@code ContextShiftGenerator} maps DEGRADED to {@code Severity.INFO}, which sits
   * below the NOTICE phone floor. So the option metrics have never once reached a phone. <b>At 60 IV
   * sessions the digest flips to OK by itself, the severity becomes NOTICE, and the full volume
   * arrives with no config change and no deploy</b> — roughly 22 trading sessions out from
   * 2026-08-20. A cap decided after that fires is a cap decided during a notification flood.
   *
   * <p>The cap bounds PHONE delivery only. The insight row is still written and WS is untouched, so
   * nothing disappears from the record or the UI — the cap decides what interrupts the owner, not
   * what is known. {@code 0} disables it.
   *
   * <p>⚠️ Known, deliberate weakness: this is a first-N budget, so a genuine 14:30 regime shift can
   * be dropped after a noisy morning has spent it. Bounding total phone volume is the thing that was
   * asked for and first-N is the only shape that actually bounds it; ranking by severity would need
   * a score CONTEXT_SHIFT does not currently carry. Raise the number rather than assume the shape.
   */
  public record Delivery(
      @DefaultValue("false") boolean ws,
      @DefaultValue("false") boolean ntfyEnabled,
      @DefaultValue("false") boolean telegramEnabled,
      @DefaultValue("NOTICE") Severity severityFloor,
      @DefaultValue("6") int contextShiftDailyPhoneCap) {

    public Delivery {
      severityFloor =
          severityFloor == null || severityFloor.compareTo(Severity.NOTICE) < 0
              ? Severity.NOTICE
              : severityFloor;
      contextShiftDailyPhoneCap = Math.max(0, contextShiftDailyPhoneCap);
    }

    static Delivery defaults() {
      return new Delivery(false, false, false, Severity.NOTICE, 6);
    }
  }
}
