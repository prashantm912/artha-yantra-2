package in.arthayantra.backtest.jobs;

import java.util.Locale;

/**
 * Job lifecycle states (§D.5 state machine). Wire/DB form is lowercase ({@code "queued"}); the enum
 * is the SCREAMING form. {@link #terminal()} marks the states where Redis Streams entries are XACKed
 * (D12 — XACK only on a terminal state).
 */
public enum JobStatus {
  QUEUED,
  RUNNING,
  COMPLETED,
  FAILED,
  CANCELLED;

  /** Lowercase form persisted in the {@code status} column and returned over the wire. */
  public String db() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Parses the lowercase DB form. */
  public static JobStatus fromDb(String value) {
    return JobStatus.valueOf(value.toUpperCase(Locale.ROOT));
  }

  /** Completed / failed / cancelled — the states that XACK the stream entry. */
  public boolean terminal() {
    return this == COMPLETED || this == FAILED || this == CANCELLED;
  }
}
