package in.arthayantra.common.web.error;

/**
 * SCREAMING_SNAKE error-code constants (A.4 / COMMON §8.3 taxonomy). Codes are grouped by family
 * prefix; the HTTP status family each maps to is fixed by the taxonomy table:
 *
 * <pre>
 * VALIDATION_*  400      AUTH_*      401/403   KITE_*    401/429/502/503
 * NOT_FOUND_*   404      CONFLICT_*  409       STRATEGY_* 400/422
 * DATA_*        422/503  INTERNAL_*  500       WINDOW_*  422
 * </pre>
 *
 * <p>Canonical-spelling pins (COMMON §3): {@code KITE_TOKEN_EXPIRED} wins over the plan's
 * {@code KITE_SESSION_EXPIRED}; {@code DATA_GAP} wins over {@code INSUFFICIENT_DATA}.
 */
public final class ErrorCodes {

  // ---- VALIDATION_* (400) ----
  public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
  public static final String VALIDATION_INTERVAL_UNSUPPORTED = "VALIDATION_INTERVAL_UNSUPPORTED";
  public static final String VALIDATION_RANGE_TOO_LARGE = "VALIDATION_RANGE_TOO_LARGE";

  // ---- AUTH_* (401/403/429) ----
  public static final String AUTH_REQUIRED = "AUTH_REQUIRED";
  public static final String AUTH_BAD_CREDENTIALS = "AUTH_BAD_CREDENTIALS";
  public static final String AUTH_SESSION_EXPIRED = "AUTH_SESSION_EXPIRED";
  public static final String AUTH_FORBIDDEN = "AUTH_FORBIDDEN";
  public static final String AUTH_RATE_LIMITED = "AUTH_RATE_LIMITED";

  // ---- KITE_* (401/429/502/503) ----
  public static final String KITE_TOKEN_EXPIRED = "KITE_TOKEN_EXPIRED";
  public static final String KITE_RATE_LIMITED = "KITE_RATE_LIMITED";
  public static final String KITE_CIRCUIT_OPEN = "KITE_CIRCUIT_OPEN";
  public static final String KITE_UPSTREAM_ERROR = "KITE_UPSTREAM_ERROR";

  // ---- NOT_FOUND_* (404) ----
  public static final String NOT_FOUND_RESOURCE = "NOT_FOUND_RESOURCE";
  public static final String NOT_FOUND_INSTRUMENT = "NOT_FOUND_INSTRUMENT";
  public static final String NOT_FOUND_SIGNAL = "NOT_FOUND_SIGNAL";
  public static final String NOT_FOUND_JOB = "NOT_FOUND_JOB";
  public static final String NOT_FOUND_WATCHLIST = "NOT_FOUND_WATCHLIST";
  public static final String NOT_FOUND_VERSION = "NOT_FOUND_VERSION";

  // ---- CONFLICT_* (409) ----
  public static final String CONFLICT_JOB_TERMINAL = "CONFLICT_JOB_TERMINAL";
  public static final String CONFLICT_VERSION_IMMUTABLE = "CONFLICT_VERSION_IMMUTABLE";
  public static final String CONFLICT_SYNC_RUNNING = "CONFLICT_SYNC_RUNNING";
  public static final String CONFLICT_WATCHLIST_NAME = "CONFLICT_WATCHLIST_NAME";
  public static final String CONFLICT_SLUG_EXISTS = "CONFLICT_SLUG_EXISTS";
  public static final String CONFLICT_NO_CONTENT_CHANGE = "CONFLICT_NO_CONTENT_CHANGE";
  public static final String CONFLICT_NOT_A_DRAFT = "CONFLICT_NOT_A_DRAFT";
  public static final String CONFLICT_HAS_PUBLISHED_HISTORY = "CONFLICT_HAS_PUBLISHED_HISTORY";
  public static final String CONFLICT_POSITION_CLOSED = "CONFLICT_POSITION_CLOSED";

  // ---- STRATEGY_* (400/422) ----
  public static final String STRATEGY_SCHEMA_INVALID = "STRATEGY_SCHEMA_INVALID";
  public static final String STRATEGY_NOT_PUBLISHED = "STRATEGY_NOT_PUBLISHED";
  public static final String STRATEGY_SCHEMA_UNSUPPORTED = "STRATEGY_SCHEMA_UNSUPPORTED";
  public static final String STRATEGY_UNIVERSE_UNSUPPORTED = "STRATEGY_UNIVERSE_UNSUPPORTED";

  // ---- closed optimizer parameter-path grammar (400) — COMMON 12.5 ----
  public static final String INVALID_PARAMETER_PATH = "INVALID_PARAMETER_PATH";

  // ---- DATA_* (422/503) ----
  public static final String DATA_GAP = "DATA_GAP";
  public static final String DATA_STALE = "DATA_STALE";

  // ---- stress-test holdout contamination (422) — S1C / §D.6 stress runs ----
  public static final String WINDOW_CONTAMINATED = "WINDOW_CONTAMINATED";

  // ---- service NOT_CONFIGURED (503) — live-port stubs in mock-built phases (A.7) ----
  public static final String NOT_CONFIGURED = "NOT_CONFIGURED";

  // ---- gateway upstream absent/unreachable (503) — A.2.2 routing envelope ----
  public static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";

  // ---- local Resilience4j limiter saturation (429) — distinct from Kite's own 429 (B-3) ----
  public static final String RATE_LIMIT_LOCAL = "RATE_LIMIT_LOCAL";

  // ---- INTERNAL_* (500) ----
  public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

  private ErrorCodes() {}
}
