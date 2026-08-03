-- The EXIT-side half of the sentiment-operand shadow measurement (companion to the
-- `sentimentLevelShadow` key on signals.fired_diagnostic / signal_rejections.diagnostic).
--
-- WHY THIS TABLE EXISTS. The scalper's two sentiment consumers — the `sentiment` confluence dot and
-- the `oi-slope-agree` rail — both read the DELTA-OI FLOW scalar `sentimentPct`, and both are pure
-- sign tests. The entry-side shadow records what each would have said on the LEVEL operand
-- (`sentimentLevelPct`). But the operand is ALSO read on the EXIT path: the E9 D4 confluence-flip
-- exit oracle (SignalEngine.confluenceFlipExit, tag `oi-confluence-exit`, armed on 12 shipped YAMLs,
-- 6 of which also arm `oi-slope-agree`) RE-RUNS the same confluence gate on every bar a position is
-- held, and that call produced no diagnostic at all. Without this table a future flow->level swap
-- could move EXIT TIMING and realized P&L while leaving no counterfactual evidence, so the eventual
-- legs->P&L comparison would be not merely incomplete but biased in an unknown direction. Found by
-- cross-vendor review of #1242; the entry-only population claim was wrong.
--
-- WHY A ROW PER EVALUATION IS AFFORDABLE HERE, WHEN V053's HEADER REJECTED EXACTLY THAT SHAPE. V053
-- rejected a row per no-entry per strategy per bar: ~7,875 evaluations/day at ~2,624 bytes a
-- rejection row (~20 MB/day) and ~63 inline writes every bar on the sole eval thread. This oracle is
-- three orders of magnitude smaller on every axis. It runs ONLY while a position is HELD, ONLY for
-- the 12 strategies carrying `oi-confluence-exit`, so the absolute worst case is 12 concurrently-held
-- positions x ~125 3m bars = ~1,500 rows/day, and in practice far fewer because scalper holds are
-- minutes, not sessions. The row is ~100 bytes of scalars, not a JSONB forensics blob: ~150 KB/day at
-- the ceiling. And it adds ZERO I/O to the eval thread — the insert is ENQUEUED onto the shared
-- BoundedAsyncWriter (ExitOracleShadowWriter), the same O(1) non-blocking seam RiskSuppressionWriter
-- uses, so a stalled DB drops counted records instead of parking the one thread that evaluates
-- signals (the #866 / 2026-07-14 zero-signals starvation class).
--
-- MEASUREMENT ONLY. Nothing here is read by any trading decision. Writing a row never changes whether
-- the oracle exits: SignalEngine computes the flip verdict from the live decision exactly as before
-- and the enqueue happens AFTER it, fail-soft. The gate call it rides on
-- (ScalperConfluenceGate.evaluateOracle) is the SAME evaluateInternal invocation the bare
-- evaluate() always made — same enforceOptionSide=false oracle semantics — merely no longer
-- discarding the diagnostic it had already built.
--
-- PARITY. The confluence gate is live-only; the deterministic replay never reaches it and the
-- backtest runner never constructs a SignalEngine, so no row is ever written on a backtest.
--
-- WHAT A NULL VERDICT MEANS. dot_would_support / slope_gate_would_pass are NULLABLE on purpose and a
-- NULL is NOT "false". It means the counterfactual could not be evaluated at all — market-data
-- published no sentimentLevelPct, or the S24 monthly-expiry suppression zeroed the whole OI block, or
-- the oracle resolved no side. Collapsing that into the live rule's fail-closed FALSE would bias the
-- measurement toward the incumbent operand on exactly the thin-data bars this exercise is about.
--
-- IST NOTE. bar_time is a timestamptz written from the bar's own IST-offset instant. Query it with
-- explicit +05:30 BOUNDS; to RENDER use AT TIME ZONE 'Asia/Kolkata' (AT TIME ZONE '+05:30' INVERTS).

CREATE TABLE exit_oracle_shadow (
  id                    BIGSERIAL PRIMARY KEY,
  entry_signal_id       BIGINT      NOT NULL, -- signals.id of the OPEN entry this oracle is guarding
  strategy_slug         TEXT        NOT NULL, -- stable identity across republishes
  bar_time              TIMESTAMPTZ NOT NULL, -- the evaluated bar (IST-offset instant)
  held_side             TEXT        NOT NULL, -- CE / PE — the side the open position holds
  evaluated_side        TEXT,                 -- the side the oracle actually scored this bar
  live_oracle_side      TEXT,                 -- the side the oracle CONFIRMED (null = no decision)
  live_flip             BOOLEAN     NOT NULL, -- did the LIVE read flip against the position (⇒ exit)
  flow_pct              NUMERIC,              -- sentimentPct — the operand the live rules read
  level_pct             NUMERIC,              -- sentimentLevelPct — the measurement-only sibling
  dot_would_support     BOOLEAN,              -- `sentiment` dot verdict on the LEVEL operand
  slope_gate_would_pass BOOLEAN,              -- `oi-slope-agree` verdict on the LEVEL operand
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- One row per (entry, bar): the oracle evaluates a given held position at most once per bar, so a
  -- replayed/retried enqueue is idempotent rather than a duplicate.
  UNIQUE (entry_signal_id, bar_time)
);

COMMENT ON TABLE exit_oracle_shadow IS
  'Per-bar counterfactual for the confluence-flip EXIT oracle: both sentiment operands (flow + level) and what each of the two sign tests would have said on the level operand, beside the live flip decision. Measurement only — never read by a trading decision.';

-- The analysis query is "every oracle evaluation for this entry, in bar order", and the UNIQUE
-- constraint's leading entry_signal_id already serves it. A session-wide sweep filters on bar_time,
-- which the table is small enough to scan.
CREATE INDEX idx_exit_oracle_shadow_bar_time ON exit_oracle_shadow (bar_time DESC);

-- ay_strategy is the READ-ONLY per-schema role; only the `artha` owner writes here.
GRANT SELECT ON exit_oracle_shadow TO ay_strategy;
GRANT SELECT ON SEQUENCE exit_oracle_shadow_id_seq TO ay_strategy;
