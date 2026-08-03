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
-- IT STORES A DECISION, NOT JUST OPERAND DISAGREEMENT (round-3 cross-vendor review). An earlier cut of
-- this table recorded only the two operand-level verdicts — "the sentiment dot would have flipped",
-- "oi-slope-agree would have passed". That is NOT sufficient and was rejected as a Critical, correctly:
-- a live PASS whose sentiment dot changes cannot be classified as still-passing versus fallen below
-- threshold, and a live BLOCK whose sentiment predicates clear cannot be classified as unblocked versus
-- still held by another rail. Such rows show disagreement while LOOKING like evidence of changed exit
-- timing, which is how a wrong money conclusion gets reached — the same bias as the original entry-only
-- sampling gap, one level down. So shadow_would_fire / shadow_oracle_side / shadow_flip carry the EXACT
-- counterfactual verdict, and the shadow_* proving columns beside them let a reader verify that verdict
-- from the row alone instead of re-deriving it.
--
-- WHY THE VERDICT IS EXACT RATHER THAN APPROXIMATE. The sentiment operand reaches the oracle's decision
-- through EXACTLY two rails: `oi-slope-agree`, and `confluence-composite` via the `sentiment` dot.
-- (ScalperGates.oiQuadrant also passes sentimentPct into its GateOutcome, but only as the REPORTED
-- operand — its verdict comes from the futures quadrant.) Every other rail is sentiment-independent, so
-- its LIVE outcome is also its counterfactual outcome and is read straight off the recorded rail matrix;
-- shadow_blocking_rail names the first such rail that blocked, and when it is non-null the counterfactual
-- cannot fire whatever the operand says. The two dependent rails are recomputed by running the REAL
-- predicates (ConnectTheDotsScorer.score / ScalperGates.oiSlopeAgree) over a context whose ONLY change
-- is the substituted operand — no formula is re-implemented and NO SECOND FETCH is made, so the
-- counterfactual provably saw exactly what the live decision saw. The side is VWAP-derived and therefore
-- sentiment-independent: a counterfactual that fires fires on the SAME side.
--
-- WHAT A NULL VERDICT MEANS. The shadow_* verdict columns are NULLABLE on purpose and a NULL is NOT
-- "false" — shadow_verdict_known says which it is. NULL means the counterfactual could not be evaluated
-- at all: market-data published no sentimentLevelPct, or the S24 monthly-expiry suppression zeroed the
-- whole OI block, or the gate blocked before any confluence was scored. Collapsing that into the live
-- rule's fail-closed FALSE would bias the measurement toward the incumbent operand on exactly the
-- thin-data bars this exercise is about. ANY analysis MUST filter on shadow_verdict_known.
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

  -- ---- what the LIVE oracle did (the incumbent flow operand) ----
  live_oracle_side      TEXT,                 -- the side the oracle CONFIRMED (null = no decision)
  live_flip             BOOLEAN     NOT NULL, -- did the LIVE read flip against the position (⇒ exit)

  -- ---- the two operands ----
  flow_pct              NUMERIC,              -- sentimentPct — the operand the live rules read
  level_pct             NUMERIC,              -- sentimentLevelPct — the measurement-only sibling

  -- ---- the COUNTERFACTUAL DECISION: what the oracle would have decided on the level operand ----
  shadow_verdict_known  BOOLEAN     NOT NULL, -- false ⇒ every shadow_* below is NULL = not evaluable
  shadow_would_fire     BOOLEAN,              -- would the level-based oracle have produced a decision
  shadow_oracle_side    TEXT,                 -- the side it would have CONFIRMED (null ⇒ no fire)
  shadow_flip           BOOLEAN,              -- would it have flipped against held_side (⇒ exit)

  -- ---- state that PROVES the verdict above from this row alone ----
  shadow_composite      NUMERIC,              -- counterfactual confluence aggregate
  composite_threshold   NUMERIC,              -- what it was judged against
  shadow_composite_valid BOOLEAN,             -- aggregate cleared threshold AND decisive legs held
  shadow_blocking_rail  TEXT,                 -- first SENTIMENT-INDEPENDENT rail that blocked live;
                                              -- non-null ⇒ no operand could have made it fire
  dot_would_support     BOOLEAN,              -- `sentiment` dot verdict on the LEVEL operand
  slope_gate_would_pass BOOLEAN,              -- `oi-slope-agree` verdict; null ⇒ tag unarmed

  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- One row per (entry, bar): the oracle evaluates a given held position at most once per bar, so a
  -- replayed/retried enqueue is idempotent rather than a duplicate.
  UNIQUE (entry_signal_id, bar_time)
);

COMMENT ON TABLE exit_oracle_shadow IS
  'Per-bar counterfactual for the confluence-flip EXIT oracle: both sentiment operands plus the EXACT side/flip the oracle would have decided on the level operand, with the state proving that verdict. Filter on shadow_verdict_known — a NULL verdict means not evaluable, never "would not fire". Measurement only, never read by a trading decision.';

-- CANONICAL QUERY — "on how many held bars would the level operand have changed the EXIT decision":
--
--   SELECT strategy_slug,
--          COUNT(*)                                              AS evaluable_bars,
--          COUNT(*) FILTER (WHERE shadow_flip <> live_flip)       AS exit_timing_changes,
--          COUNT(*) FILTER (WHERE shadow_flip AND NOT live_flip)  AS exits_added,
--          COUNT(*) FILTER (WHERE live_flip AND NOT shadow_flip)  AS exits_removed
--     FROM strategy.exit_oracle_shadow
--    WHERE shadow_verdict_known
--      AND bar_time >= timestamptz '2026-08-04T09:15:00+05:30'
--    GROUP BY strategy_slug;
--
-- `exit_timing_changes` is the number this table exists to produce, and it is a DECISION delta, not an
-- operand delta. Join each changed bar back to signals/paper_positions for the P&L half.

-- The analysis query is "every oracle evaluation for this entry, in bar order", and the UNIQUE
-- constraint's leading entry_signal_id already serves it. A session-wide sweep filters on bar_time,
-- which the table is small enough to scan.
CREATE INDEX idx_exit_oracle_shadow_bar_time ON exit_oracle_shadow (bar_time DESC);

-- ay_strategy is the READ-ONLY per-schema role; only the `artha` owner writes here.
GRANT SELECT ON exit_oracle_shadow TO ay_strategy;
GRANT SELECT ON SEQUENCE exit_oracle_shadow_id_seq TO ay_strategy;
