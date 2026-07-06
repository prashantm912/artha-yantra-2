# Full codebase audit — 2026-07-05 (post swing-families build)

**Scope:** everything shipped since the previous full audit closed (#509) — per-strategy paper
books (#566/#568), Minervini SEPA track (screener/geometry/funnel/live engine/backtests,
#524–#563), Manas Arora family (#565–#574), F9 paper-risk layer (#510/#576), F7 auto-promotion
(#577), SignalEngine reconcile fix (#579–#581), bulk export (#584), chart persist (#583) — plus a
fresh pass over security, performance, testing, devops, maintainability, and UI/a11y.

**Method:** 3-phase multi-agent audit. Phase 1: 8 parallel recon mappers (~1.2M tokens). Phase 2:
8 adversarial verifiers over every high/medium recon finding, 5 targeted unknown-resolvers, 6
dimension finders each with its own verification pass (25 agents, ~3.8M tokens, 976 tool calls).
Every finding below carries a verifier verdict (CONFIRMED / PARTIAL / REFUTED) with re-graded
severity. Refuted findings are listed at the end for honesty.

**Verdict in one line:** the platform's engineering discipline held (parity firewall, typed
records, flag-gating, migrations, SQL hygiene all clean) — but the new swing tracks shipped with
a broken nightly data dependency, two unmanaged-position lifecycle holes, exit-math drift between
live and the backtests that justified go-live, and zero tests on the Manas live engine. Nothing
found touches real money (paper-only platform); several findings corrupt the forward-paper
evidence the owner's go-live decisions depend on.

---

## 1. Executive summary

**No critical findings** (critical = wrong money movement; the platform places no live orders).
**12 HIGH** confirmed, **~30 MEDIUM**, **~28 LOW**, 5 notable refutations.

The three most consequential clusters:

1. **The Minervini nightly chain is silently broken for unattended weekdays.** The 19:30 screen
   fires the same second as the 19:30 bhavcopy backfill it depends on, so it screens *yesterday*;
   by 20:00 the funnel resolves `asOf=today` and finds **zero rows** → the swing batch fires zero
   entries every unattended trading evening, logging a normal-looking "0 entries". Every
   successful run so far (Fri/Sat) was manual/restart-assisted. First unattended exposure:
   **Mon 2026-07-06 20:00 IST**. Compounding it: the screener plane reads CA-**unadjusted**
   bhavcopy (splits/bonuses poison SMA/52wk/RS gates and VCP geometry for ~a year per event),
   while the live engine reads the CA-adjusted candles plane — the two planes disagree about the
   same symbol.

2. **Open swing positions can become unmanaged.** Republishing (or a seeder YAML resync on boot,
   or a compile failure) orphans open anchors: the exit pass keys anchors by *current published
   version id* and silently skips misses — no stop/trail is ever evaluated again, the position
   vanishes from the sell-decision report, and the symbol becomes re-enterable (double exposure).
   The Manas PR-H/PR-I republishes mean **pre-#573 anchors are orphaned today**. A second hole:
   the exit-commit→event-publish window (plus catch-and-log on close failure) can strand an OPEN
   paper position with its anchor already EXPIRED — nothing revisits it.

3. **Live-vs-backtest exit parity was not carried to the swing families.** The Manas ATR trail
   diverges from the backtest three ways (arm on HIGHS vs CLOSE, ATR pinned at entry vs rolling,
   no breakeven floor vs floored) while the YAML claims "same math as the BACKTEST"; live engines
   evaluate exits on the entry bar, both backtests never do; live volume gate is 20-day vs the
   backtest's 50-day; and there is **no swing exit-equivalence fixture** (the premium family has
   one — the pattern existed and wasn't replicated). Forward-paper vs backtest A/B will read
   exit-math drift as "edge decay".

Cross-cutting: the F7 graduation PnL join cross-attributes fills between strategies sharing a
symbol (re-graded HIGH — it is the promotion evidence base); the paper-book split is solid in the
write path but **untested** as isolation; the FE ships a one-click unconfirmed ledger wipe; the
EOD batch chain has no failure alert or did-not-run canary; and the stale-jar deploy trap has no
mechanical guard (it already fired once).

What is genuinely good: gateway deny-by-default + allowlist fully covers every new endpoint
(verified exhaustively); zero SQL injection across all new JdbcTemplate code; typed-record ratchet
held; all six arming flags default-OFF with compose passthroughs; frozen goldens byte-identical
through #573's additive exit params; V007 dedupe migration verified sound; backup pipeline drilled
weekly in CI; virtual-thread scheduler kills the whole scheduler-starvation class.

---

## 2. Phase 1 — architecture review

### System shape
Four Java services + Python optimizer + margin appliance behind a loopback-only edge-gateway
(session cookie + XSRF, deny-by-default; internal service APIs deliberately unauthenticated —
compose network is the trust zone). React 19 SPA. TimescaleDB with 4 independent Flyway lineages;
Redis for ticks/streams. Mock/live = separate DBs + Redis logical DBs derived by `ay.ps1`.

### New moving parts since #509
- **Books:** 5 capital buckets in `strategy.paper_account` (scalper/minervini/manas-arora/manual/
  other, ₹1.5L each), risk_settings composite-keyed `(book,key)`. Book identity is *derived from
  `strategies.tags`* (never configured): `Books.fromTags` (in-memory, engine gate) and
  `BookResolver.bookForSignal` (SQL CASE, charge time) — two copies of the same precedence.
  Signals carry no book column; the page filters via a read-time tags join (third, semantically
  different, containment-based copy).
- **Two swing families**, each: bhavcopy screener + geometry (marketdata schema) → funnel
  (buyable [0.98–1.05]×pivot / on-deck / watch) → strategy-signal daily EOD batch engine
  (20:00 / 20:05 IST, flag-gated) reusing the FROZEN evaluators over fresh 1d bars → auto-paper
  into the family book → exit pass re-evaluates nightly. Deep-history backtests (~11y, candles@1d)
  live *inside market-data-service* (deliberate single-writer choice; backtest-service does not
  own them).
- **F9:** SPAN via Upstox `/v2/charges/margin` (fail-soft typed records); advisory `advised_lots`
  + `margin_snapshot` always-on; heat-cap gate in `RiskService.entryAllowed` behind
  `artha.paper.risk.enabled` (OFF). **F7:** graduation scheduler 21:00 IST behind
  `artha.graduation.promotion-enabled` (OFF), measurement-only (verified: zero writes possible
  with flag off; no HTTP path invokes evaluate()).

### Data flow (swing, the new spine)
bhavcopy 19:30 → screen 19:30/19:40 (**race — see A1**) → geometry upsert → funnel SQL →
20:00/20:05 batch: funnel REST + per-symbol 520d candles REST (cache-first; today's 1d bar comes
from the batch's own Kite tail re-fetch, not bhavcopy) → frozen EntryEvaluator on last bar →
signals row + side-channel + suggested_qty (one tx) → AutoPaperListener CAS → paper position at
daily close → nightly exit pass → SignalExited(price=close) → settle. UI reads per-book pages.

### Mock/live separation
Held. Fail-closed `${ARTHA_DB_NAME:?}` interpolations; mock stack returns `unpriced` margins
(ObjectProvider); no new cross-schema coupling; V021-V024/V033-V037 all forward-only, zero edits
to applied migrations (verified via git).

### Top architectural risks (all verified in Phase 2)
1. Version-keyed anchor lifecycle (publish/resync orphans) — both swing engines.
2. Wall-clock-only EOD chain with an internal dependency race and no completion handshake/alerts.
3. Two CA-adjustment planes (raw bhavcopy screener vs adjusted candles engine).
4. Exit-doctrine duplication (ExitEvaluator vs hand-rolled sims) with prose-only coupling.
5. Book precedence encoded 3× (Java, SQL CASE, tag-containment filter).
6. ~75–85% code duplication between the two families; family #3 would triple it.

---

## 3. Frontend audit

**Working well:** book named in every per-book page h1/subtitle; QueryState error-vs-empty
discipline on all 7 new pages; book in every query key (no cache collisions); decimal-string
money discipline; LWC autoSize + theme containment; MegaMenu keyboard-operable; AdvanceChart
persist wiring sound (URL wins, no render loops); graduation Money component uses sign+colour.

**Broken / risky (verified):**
- **[HIGH]** Reset ledger = one bare click; FE hardcodes `confirm:true` neutralizing the server
  guard ([PaperPage.tsx:256](frontend-react/src/pages/paper/PaperPage.tsx:256),
  [paper.ts:200](frontend-react/src/api/paper.ts:200)). Wipes the current book's open+closed
  ledger — the F7/reliability evidence. Siblings (journal/watchlist) got confirms in Wave 4; this
  was missed. Recovery = last-midnight pg_dump only.
- **[MEDIUM]** STOMP frames merge into book-filtered signal caches with no family check — frame
  *can't* be checked (no book field in the payload). Wrong-family rows atop per-book feeds until
  refetch. Additive fix verified safe end-to-end (publisher→gateway relay→FE cast).
- **[MEDIUM]** Cockpit "Paper book" panel: bookless hooks = all-books aggregate (equity/exposure
  include swing holdings) while risk chips are scalper-only, framed as the scalper book — wrong
  intraday sizing input on the primary live screen.
- **[MEDIUM]** Kill-switch tooltip claims "blocks all new paper entries" but the toggle PUTs the
  current book only — swing batches keep opening after the owner "pauses". (Telegram /pause exists
  precisely because one book ≠ global.)
- **[MEDIUM]** Minervini decision-support is API-only: sell-decisions triad, report card, and both
  swing-backtest endpoints have no route/hook (Manas got a full page). The pinned daily sell
  workflow for the live positions has no UI.
- **[MEDIUM]** Graduation Win% renders the raw fraction ("0.7500" under a "Win%" header).
- **[MEDIUM]** Screener Recompute refetches the screen but never invalidates funnel/candidate
  caches — stale buyable list on the tab that just claimed freshness (bounded by 30s staleTime on
  remount, but persists while mounted).
- **[MEDIUM]** `<tr role="button">` on signals rows destroys table semantics for AT (no axe spec
  covers /signals, so CI never sees it).
- **[LOW]** Bare `/paper` defaults to scalper while bare `/signals` = all books; duplicate menu
  entries; no navigable ledger for manual/other books and no aggregate closed-trades view
  anywhere; colour-only gate chips (candidate pages solved this with ✓/✕ — regression vs own
  convention); `text-ay-muted/70` %-to-pivot fails 4.5:1 on 4/5 themes; role=tab without tabpanel
  contract; no sticky thead on 200-row screener tables; Manas backtest "running" never refreshes
  and isn't announced; equity curve canvas-only; chartPrefs restore has no shape validation;
  hand-written minervini/manas/graduation wire types not added to contracts.bridge.ts (the
  silent-em-dash class the bridge exists to kill).

---

## 4. Backend audit

**Working well:** frozen evaluators reused verbatim by both engines (parity firewall intact,
goldens byte-identical through #573); one-tx signal emission (row + qty + side-channel); CAS
close (`AND status='OPEN'`) kills double-settle races (#580); per-book governor set fully
parameterized; swing exclusion from the 15:45 sweep via the config style path (lockstep with
StrategyCompiler verified); PR #580's six fixes all present and correct; #581 regression IT real.

**Broken / risky (verified):**
- **[HIGH] Screen/bhavcopy race → empty funnel on unattended evenings.**
  [MinerviniScheduler.java:47](services/market-data-service/src/main/java/in/arthayantra/marketdata/screener/minervini/MinerviniScheduler.java:47)
  fires 19:30 concurrently with the async bhavcopy walk it depends on; screen keys on
  `max(trade_date)` = yesterday; the 20:00 funnel resolves `asOf=today` → zero screen rows →
  zero entries, silent. Manas (19:40/20:05) has ~10 min accidental headroom, same class. Fix:
  chain screen off bhavcopy completion (or cron ≥15 min later) AND make the funnel default to
  `max(screen_date)`.
- **[HIGH] Version-keyed anchor orphans (both engines).** Exit pass + sell-decision + heldSymbols
  all key on *current* publishedVersionId; publish/resync/compile-fail silently skips → no stop
  evaluation ever again + symbol re-enterable. Manas pre-#573 anchors orphaned today. Fix:
  resolve anchors by strategy (compile the anchor's own version config — `versionConfig()`
  exists) + loud ntfy when an active anchor resolves to nothing manageable.
- **[HIGH] Once-per-batch risk gate.** `entryAllowed(book)` checked once before the candidate
  loop; MAX_OPEN/deployment see only pre-batch state; YAML `max_positions` enforced nowhere live.
  25 candidates → ~162% of the book in one evening (first live run: 8 entries ≈ 52% with zero
  mid-run governor influence). Fix: move the check inside the loop.
- **[HIGH] F7 graduation cross-attribution.** `closedPnls` joins paper_orders on
  (book,exchange,tradingsymbol,side) with no time bound and no order→position FK — the same
  realized PnL enters every sibling strategy's series (12+ scalpers share ATM strikes; V021's
  per-book open-uniqueness makes siblings *average into the same position* by design). A strategy
  can GRADUATE on a sibling's fills. Fix: minimally time-bound the join; properly add
  `paper_orders.position_id` (new migration).
- **[MEDIUM] Reconcile never retries transient load failures** — and worse than recon claimed:
  `reload` replaces `this.loaded` wholesale, so a mid-session reload during a market-data restart
  unloads *every* strategy (halting EXIT evaluation) until the next registry change/08:40, while
  the snapshot hides the drift. Fix: exclude transiently-failed version-ids from
  `lastReloadedPublishedSet` so the 20s reconcile keeps retrying.
- **[MEDIUM] Exit-commit→publish orphan window** (both engines): EXIT row + EXPIRED committed,
  then publish; close failure is also catch-and-log — either way the paper position stays OPEN
  forever (equities don't tick; sweep is intraday-only). Fix: idempotent reconcile step in the
  daily batch (OPEN position + EXPIRED anchor with newer EXIT row → settle at that EXIT price).
- **[MEDIUM] Swing MTM blind:** non-ticking equities mark at cost — equity/dayPnl/daily-loss/
  sizing never see open drawdown. Fix: candles@1d close fallback in `unrealizedTotal`.
- **[MEDIUM] Silent exit-pass skip on failed candle fetch:** empty list on any REST failure is
  cached for the run; a held symbol's stop simply isn't evaluated that day. Fix: empty series for
  a HELD anchor = error + ntfy + one retry.
- **[MEDIUM] Entry-bar exits (live) vs never (backtests)** — real churn path for Manas fast-move
  (entry and same-close exit while the backtest books next-bar PnL).
- **[MEDIUM] Margin-heat endpoint prices all books as one basket** (one equity leg → whole quote
  unpriced; >20 rows → silent truncation). Loud failure, but the advisory-week arming input is
  dead whenever any swing position is open (i.e. always, now). Fix: `?book=` + NFO/BFO filter.
- **[LOW]** Books precedence triplication; free-text book on writes (400 on unknown); manual
  orders bypass entryAllowed (documented?); Telegram killSwitchAllBooks omits OTHER + /status
  scalper-only; heat-cap trip dedup never re-arms after a limit change (NUL/space delimiter
  mismatch); `SwingRun.candidates` hardcoded 0 (both engines); armed heat-cap = sync 2-hop HTTP
  on the eval thread (bounded ~3.5s, memoize when arming); graduation one-way ratchet (semantics
  = "ever graduated" — label it); advised_lots stores units not lots (dashboard comparison unit
  mismatch); PaperMarginAnnotator unconditional on the shared Upstox limiter + notifierExecutor.

---

## 5. Data & migrations audit

**Working well:** 12 new migrations, all forward-only, zero edits to applied files (git-verified);
V020/V022 side-channels correctly outside frozen score_breakdown; V024 same-schema FK; V007
dedupe verified sound (NOT NULL job_id, cascade, keep-newest); no cross-schema FKs anywhere;
single-writer held for every new table; V021 is a model migration doc-wise (owner rulings inline).

**Risky (verified):**
- **[MEDIUM]** V021 wiped ALL paper history (open AND closed) on live+mock — owner-approved, but
  "start flat" only required deleting OPEN rows; the ≥50-trade F7 clock restarted 2026-07-05.
  Recovery: pre-migration pg_dump (nightly exists). Document the clock reset or restore closed
  rows with a book backfill.
- **[MEDIUM→latent]** `book DEFAULT 'manual'` + fail-open governors on missing (book,key) rows:
  the one ungoverned book is the schema default; a *future* Books constant without seed rows
  can't even be killed (`boolFlag(KILL_SWITCH).orElse(false)`). Fix: startup/IT assertion every
  book has kill_switch + auto_paper rows (or fail-closed when a book's row set is empty); drop
  the DEFAULT in a follow-up migration.
- **[LOW]** V021 DELETE FROM risk_settings discarded owner-tuned limits with no audit rows;
  pre-split risk_audit rows (book NULL) invisible to every per-book audit tail; screener tables
  grow unbounded (~550k rows/yr class, no pruner — JobPruner pattern exists); V023 silently
  tightened scalper daily_loss 10%→3% with no audit row; RiskService.java literal NUL bytes make
  the file invisible to ripgrep (this audit's own greps skipped the file that defines
  entryAllowed).

---

## 6. Security audit

**Working well (verified exhaustively):** zero SQL injection in all new JdbcTemplate code (every
query parameterized, offsets clamped, typed-boolean fragments); gateway deny-by-default with all
six new endpoint prefixes allowlisted (60+ class-level mappings swept — nothing escapes; the
GatewayRouteAllowlistTest pins drift); Telegram bot fail-closed triple gate + /confirm + pre-boot
drain + V019 audit; secrets hygiene (tokens in headers only, status-only error logs); typed
records + fail-soft held; F9/F7 default-OFF; CAS close; CSRF centrally echoed incl. bulk export.

**Findings:**
- **[MEDIUM CONFIRMED]** Telegram bot token leaks into WARN logs on transport failures —
  `TelegramApi` embeds the token in the URI path and logs `e.getMessage()`; ResourceAccessException
  keeps the path. Token ≠ command execution (chat-id allowlist holds) but full Bot-API control;
  vector = logs pasted into issues/AI sessions. Redact `/bot<token>` in both catch blocks.
- **[MEDIUM]** Reset-ledger one-click (see FE — also a destructive-action security concern).
- **[LOW]** Bulk export builds the whole ZIP in heap (multi-GB possible on the live-capture JVM;
  two prior OOM incidents) — stream or budget it. Zip entry names/Content-Disposition unsanitized
  (defense-in-depth). Telegram auth is chat-scoped not sender-scoped (a group chat id would hand
  /flatten to all members — document private-ids-only). Free-form book on writes. chartPrefs
  blind-cast. Trust model (internal APIs unauthenticated; dev-tools socat pierces to 127.0.0.1)
  is pre-existing and unregressed — document it; never co-run dev-tools with live.

The owner's internal-publish flow (docker exec wget) is consistent with the documented trust
model (compose network = trust zone) — not a regression, but it bypasses the gateway audit trail;
prefer the authenticated path for publishes now that it exists.

---

## 7. Performance & reliability audit

**Working well:** DoubleBag hygiene complete in both backtest services; single-run CAS guards with
finally-reset; virtual-thread scheduler (`spring.threads.virtual.enabled=true`) — the whole
"single scheduler thread starves capture" class is impossible (finding REFUTED); bhavcopy
scheduler is the model citizen (own executor, CAS, failure isolation); timeout discipline layered
right (1.5s/2s on the emission path); swing batch heap-light with a shared per-run series cache;
screens are single DB-side window queries (no N+1); Redis capped + streams trimmed.

**Findings:**
- **[MEDIUM]** Both deep backtests can run concurrently on the 448MB market-data heap during
  market hours (no shared bulkhead, no session gate) — ~150–200MB retained combined next to live
  capture. Fix: shared Semaphore(1) + 409 during session.
- **[MEDIUM]** Backtest daemon threads catch RuntimeException only — an OOME leaves
  status="running" forever, no alert. Fix: catch Exception + failed-report finally +
  ExitOnOutOfMemoryError.
- **[MEDIUM]** The silent exit-pass skip on fetch failure (see Backend) is also the reliability
  headline: the daily batch is the *only* stop evaluator.
- **[LOW]** notifierExecutor (1–2 threads, queue100, Abort) shared by ntfy + after-commit margin
  annotations — full queue throws inside afterCommit; use DiscardOldest or a dedicated executor.
  Uncached 30s-timeout instrument-meta lookup per ENTRY on the single eval thread — cache + tight
  timeouts. ~3,600 serial per-symbol queries per backtest run on the shared DB (evening soft
  squeeze). Signals book filter = correlated EXISTS per row (fine now; denormalize later).
  No retention on signal_rejections/shadow_positions/screen tables (year-one safe).

---

## 8. Testing audit

**Working well:** ExitEvaluator ATR/trail/square_off well covered incl. a real end-to-end YAML
golden (ManasAtrExitsTest via the production runner, outside the frozen set); Minervini engine
features all behaviorally tested; MinerviniSwingEngineTest pins the right things (arming,
frozen-evaluator entry/exit); F9 margin tested at both ends with the live-verified wire shape;
graduation math unit-tested; #581 reconcile IT real; contract capture + Map ratchet see the whole
new surface; ManasGates boundary-tested; CI shards healthy (~4m/3.5m/2.2m).

**Gaps (all verified):**
- **[HIGH]** ManasAroraSwingEngine / SellDecisionService / Scheduler: **zero tests** — the engine
  that fires real entries into the live book daily; its Minervini twin's review caught real bugs
  in exactly this glue.
- **[HIGH]** Per-book isolation untested: V021's per-book unique index, real bookForSignal SQL,
  Books.fromTags precedence, aggregate-vs-books math, book-scoped reset — every paper test uses
  book='scalper' and mocks the resolver. A routing typo = silently zero cross-book errors with
  all tests green.
- **[MEDIUM]** No swing exit-equivalence fixture (premium pattern not replicated) — and the sides
  *already* diverge (Manas trail). One fixture consumed by both suites, per #505.
- **[MEDIUM]** Deep-history sims have no frozen-output golden — the published 28–43% CAGR /
  +5.1%/trade evidence can silently shift under refactor; "v1 byte-reproduces" was a manual check.
- **[MEDIUM]** No e2e for any new page; PaperPage spec has zero `book` occurrences.
- **[LOW]** PaperMarginAnnotator untested (advisory-only blast radius); armed-trail ratchet
  monotonicity + same-bar arm/breach untested; MinerviniGates has no dedicated unit test (shared
  by 3 consumers); market-data shard at 178 classes — add a timeout tripwire.

---

## 9. DevOps & deployment audit

**Working well:** fail-closed `${VAR:?}` profile isolation; all six headline flags passthrough +
default-OFF; three-layer swing disarm (flag, guard, publish state); backup pipeline hardened and
**drilled weekly in CI** (round-trip with hypertable/cagg assertions + drift tripwire); ci-e2e
builds every compose image on every PR (the "CI never builds images" claim REFUTED); it-naming
guard; `ay tag-images` rollback primitive; Manas scheduler is the converged default-OFF template.

**Findings:**
- **[HIGH]** Stale-jar trap has zero mechanical guard: Dockerfiles COPY host-built jars, no
  build-info/git sha anywhere, `:dev` mutable — a running container cannot be interrogated for
  its commit, and tag-images can even mislabel a stale image with HEAD's sha. Already fired once
  (Manas go-live). Fix: spring-boot build-info or GIT_SHA ARG → /actuator/info + deploy-step
  sha assertion.
- **[HIGH]** EOD batch chain (screens → swing → graduation) has no failure ntfy and no
  did-not-run canary; container-down at 20:00 = silent no-run (no catch-up), and both scheduled
  agent runs (15:47/09:42) miss the window. Missed exit passes = stops fire days late = corrupted
  forward evidence. Fix: ntfy in every scheduler catch + next-morning marker check (backup
  dead-man pattern).
- **[MEDIUM]** A whole tier of knobs is unsettable without editing compose: graduation thresholds,
  paper-risk sizing, every batch cron (application.yml references env keys compose never passes)
  — an owner's `.env` value is silently ignored, the exact class hit twice before. Add
  default-preserving passthroughs *before* arming F7/F9.
- **[MEDIUM]** No backup-before-migration convention; 3-slot retention rotates a deliberate
  pre-migration dump out in 3 days; V021 is the destructive precedent; pg_dump-vs-ALTER lock
  stall known only in session memory. Add to the new-migration skill + exempt manual dumps.
- **[LOW]** Minervini screen flag default-ON with no passthrough (only always-on batch —
  `.env=false` silently ignored); `.env.example` missing all new flags except the Minervini trio;
  swing disarm requires container recreate (document the 4-step ladder); market-data CI shard
  timing tripwire.

---

## 10. Maintainability audit

**Working well:** magic numbers consistently @Value-tunable under per-family prefixes (the entire
Manas exit doctrine is ~20 keys); genuine reuse where it matters (frozen evaluators, SwingPortfolio,
VcpDetector, geometry); cross-family isolation explicit and mirrored; V021 model migration
comments; deliberate sibling divergences documented with reasons.

**Findings:**
- **[MEDIUM]** Fork debt: ~75–85% duplication across 10+ Minervini↔Manas file pairs (~2,700 of
  ~5,000 lines mechanical rename); drift already demonstrated (#573 missed 6 sibling spots).
  Family #3 needs a parameterized swing-family layer first (engine/sell-decision/funnel/reporting
  skeletons; keep gates/geometry per-family).
- **[MEDIUM]** Stale exit-doctrine comment cluster in ManasAroraSwingEngine (4 sites) — includes
  a now-false "exits do not read entryIndex" claim that invites deleting a load-bearing guard.
  Comment-only PR.
- **[MEDIUM]** ManasAroraSellDecisionService reports sma20 as "trail level" — a plausible wrong
  number on the owner's daily read (the real trail is peak−2×entry-ATR when armed). Shared
  ExitEvaluator.trailLevel helper so display can't diverge.
- **[MEDIUM]** RiskService NUL bytes + three dedup keys with two delimiters → heat-trip dedup
  never re-arms after a limit change (suppressed audit+ntfy on same-day re-breach) AND the file
  is grep-invisible. One visible `dedupKey(book,key)` helper.
- **[LOW]** PaperAccountRepository "other has no row" comment contradicts V021 (doubly-dead
  fallback); Manas→Minervini type coupling invisible to Modulith (screener = one module) — lift
  SwingPortfolio/geometry/BtTrade into screener.swing; duplicate byte-identical backtest variants
  + advertised-but-degraded v4 float (documented, but /compare shows same-number rows);
  SwingRun.candidates=0; deep backtests in market-data (deliberate; standardize report shape
  later); orphaned endpoints (Minervini backtest GETs FE-unused).

---

## 11. Prioritized fixes

**P0 — before the next unattended session (Mon 2026-07-06 20:00 IST):**
1. Screen/bhavcopy race: shift screen crons after bhavcopy completion (or chain them) + funnel
   defaults to `max(screen_date)`. Until fixed: manually POST /run after 19:35 each evening.
2. Anchor orphans: strategy-keyed (not version-keyed) exit pass + heldSymbols in BOTH engines +
   ntfy on unmanageable anchor. Sweep for currently-orphaned Manas anchors and re-adopt/close.
3. Exit-pass fetch failure = loud error + retry, not silent skip.
4. EOD chain ntfy on every scheduler catch + did-not-run canary.

**P1 — this week (evidence integrity):**
5. Swing exit-equivalence fixture + align the Manas trail (pick doc §3.5B semantics, change the
   divergent side, fix the false YAML/javadoc claims) + entry-bar exit-skip guard + volume gate
   20↔50 alignment.
6. Graduation join: time-bound now, `position_id` FK migration next.
7. Per-batch risk gate inside the candidate loop.
8. Reset-ledger confirmation dialog; kill-switch wording; margin-heat `?book=`.
9. CA-adjust the screener plane (join eod_corporate_actions or read the adjusted path) + RS
   tie-break (`thenComparing(symbol)` + midpoint percentile).
10. Manas engine + book-isolation test suites.

**P2 — before arming F9/F7:**
11. Compose passthroughs for thresholds/sizing/crons; .env.example refresh; build-info/GIT_SHA
    stale-jar guard; heat-trip dedup delimiter fix; Telegram token redaction + OTHER book;
    advisory heat ntfy actually wired (currently more inert than its contract).

**P3 — structural (before family #3):**
12. Swing-family extraction; sim goldens; e2e for new pages; retention pruners; Minervini
    sell-decisions FE panel; a11y batch (tr role, chips, contrast, tabs).

---

## 12. All issues by severity

### CRITICAL
None. (No real-money path exists; the findings that would qualify on a live-order platform are
the HIGH lifecycle/evidence items below.)

### HIGH (12 — all verifier-CONFIRMED)
| # | Issue | Where |
|---|-------|-------|
| H1 | Minervini 19:30 screen races its own bhavcopy dependency → 20:00 funnel empty on unattended weekdays; screens run a day late | MinerviniScheduler.java:47, TrendTemplateService.java:73-78, MinerviniFunnelService |
| H2 | Republish/resync/compile-fail orphans open swing anchors — no stop evaluation, vanishes from sell-decisions, symbol re-enterable (Manas pre-#573 anchors orphaned today) | MinerviniSwingEngine.java:286-288/:404-439, ManasAroraSwingEngine.java:317-320, seeders |
| H3 | Risk gate once-per-batch; YAML max_positions unenforced live — one evening can deploy >100% of a book | MinerviniSwingEngine.java:152-154, ManasAroraSwingEngine.java:176, RiskService.java:89-127 |
| H4 | Manas live ATR-trail ≠ backtest (arm anchor, ATR pinning, breakeven floor) while YAML claims "same math"; +5.1%/trade evidence computed under different exits | ExitEvaluator.java:458-487 vs ManasAroraSwingBacktest.java:301-371 |
| H5 | F7 graduation PnL join cross-attributes fills between strategies sharing a symbol — promotion evidence polluted | GraduationService.java:111-129 |
| H6 | Screener plane reads CA-unadjusted bhavcopy — splits poison SMA/52wk/RS gates + VCP geometry for ~1yr per event; engine plane adjusted → planes disagree | TrendTemplateService.java:98-149, ManasScreenService.java:98-144, DailyBarReader.java:18-27 |
| H7 | One-click unconfirmed ledger reset (FE hardcodes confirm:true) | PaperPage.tsx:256-262, paper.ts:200-201 |
| H8 | Cheat-3c setup is a synthetic proxy mislabelled as the doc's cheat (low-cheat structurally unreachable: funnel requires 8/8 incl. price>50d the doc waives) | VcpDetector.java:101-106, MinerviniFunnelService.java:55 |
| H9 | Stale-jar deploy trap has zero mechanical guard (no build-info/sha; :dev mutable; already fired once) | services/*/Dockerfile, ay.ps1:240-255 |
| H10 | EOD batch chain: no failure ntfy, no did-not-run canary, no catch-up | all 5 batch schedulers |
| H11 | ManasAroraSwingEngine/SellDecisionService/Scheduler have zero tests | strategy-signal src/test |
| H12 | Per-book paper isolation untested (unique index, real resolver SQL, precedence, aggregate math, reset scoping) | V021 + paper test suite |

### MEDIUM (~30 — verifier-CONFIRMED or PARTIAL-with-real-residue)
| # | Issue |
|---|-------|
| M1 | Margin-heat prices all books as one basket; dead whenever a swing position is open; >20-leg silent truncation (downgraded from high: loud failure) |
| M2 | Reconcile never retries transient load failures; mid-session reload during market-data restart unloads ALL strategies (exit evaluation halts) |
| M3 | Exit-commit→publish window + catch-and-log close failures strand OPEN positions with EXPIRED anchors (no reconcile) |
| M4 | Swing book MTM blind — non-ticking equities mark at cost; daily-loss/sizing never see open drawdown |
| M5 | Exit pass silently skipped for a held symbol on candle-fetch failure (only stop evaluator) |
| M6 | Live engines evaluate exits on the entry bar; both backtests never do (Manas fast-move same-close churn) |
| M7 | No swing exit-equivalence fixture (premium pattern not replicated); prose coupling already factually wrong |
| M8 | Manas live volume gate 20-day vs backtest 50-day — different trade populations |
| M9 | RS-rank universe/convention differs live vs backtest (filtered bhavcopy ordinal vs unfiltered survivor midpoint) |
| M10 | Deep-sim cost model never compounds order size — net CAGRs optimistic, worst for rs-only |
| M11 | Survivorship + universe mismatch (disclosed, but every quoted number carries it) |
| M12 | RS tie-rank nondeterministic (no ORDER BY + ordinal percentile; zero-return tie blocks real) |
| M13 | Holiday/stale-bar runs: no MarketCalendar guard; late-fires and exited-symbol re-entry at stale closes |
| M14 | No daily-bar freshness assertion; stale:true flag dropped by the client |
| M15 | V021 wiped ALL paper history incl. closed (owner-approved; F7 clock reset undocumented) |
| M16 | book DEFAULT 'manual' + fail-open governors on missing rows; future unseeded book can't be killed |
| M17 | STOMP frames pollute book-filtered signal caches (no book field in frame) |
| M18 | Cockpit panel: all-books aggregate framed as scalper + scalper-only risk chips |
| M19 | Kill-switch labeled "Master" but per-book; swing batches keep trading after "pause" |
| M20 | Minervini sell-decisions/report-card/backtests have no FE surface (daily sell workflow is curl-only) |
| M21 | Graduation Win% renders raw fraction as "Win%" |
| M22 | Screener Recompute leaves funnel/candidate caches stale |
| M23 | `<tr role="button">` destroys signals-table semantics for AT |
| M24 | Telegram bot token leaks into WARN logs on transport failures |
| M25 | Two deep backtests concurrently on the 448MB live-capture heap; no bulkhead/session gate |
| M26 | Backtest daemon Error → status stuck "running" forever, no alert |
| M27 | Deep sims have no frozen-output golden (published evidence numbers silently mutable) |
| M28 | No e2e for any new page; PaperPage book dimension unasserted |
| M29 | Ops knob tier unsettable without compose edits (graduation thresholds, risk sizing, crons) — silent-ignore class |
| M30 | No backup-before-migration convention; 3-slot rotation evicts deliberate pre-migration dumps |
| M31 | Fork debt ~75-85% across 10+ file pairs; #573 already missed 6 sibling spots |
| M32 | Stale Manas exit-doctrine comments incl. false "exits don't read entryIndex" |
| M33 | Sell-decision trailLevel = sma20 (not an exit input post-#573) — plausible wrong number on the daily read |
| M34 | RiskService NUL bytes + mixed dedup delimiters → heat-trip dedup never re-arms; file grep-invisible |
| M35 | Manas liquidity depth 25× vs doc's mandatory 50× (downgraded: config-tunable, veto bounds worst case) |
| M36 | Minervini 50d-MA trail armed from day 1 (doc: only once MA reaches entry) — early exits bias reliability stats |
| M37 | Power Play missing flag-depth/duration caps; thrust adjacency-free |
| M38 | Primary Base = generic 52wk-high breakout (no IPO/first-base data exists) — mislabelled edge |
| M39 | VCP detector lacks base-depth (≥60% reject) and 3–65wk duration caps — feeds every live pivot |
| M40 | Manas live lacks pyramiding/open-risk-cap/circuit breakers vs the pyramid-variant headline number |

### LOW (~28)
Signals book filter ≠ charging precedence (dual-tag/other invisible) · free-text book on writes ·
Telegram OTHER omission + /status scalper-only · ghost-book no-op capital edits ·
stale-bar freshness polish (mainline dup blocked) · entry-bar churn (Minervini side; near-inert) ·
"other has no row" stale comment · pre-split risk_audit rows invisible per-book ·
screener/rejections/shadow tables unbounded (year-one safe) · V023 silent daily-loss tighten ·
V021 CHECK-drop name-guessing pattern · bulk-export heap ZIP + unsanitized names ·
Telegram chat-scoped auth · chartPrefs blind cast · trust-model documentation (dev-tools socat) ·
notifierExecutor choke point · uncached 30s meta lookup on eval thread · serial/N+1 backtest series
reads (`ManasAroraBacktestService.readSeries` + the Minervini twin issue ~1,800 per-symbol DB
round-trips; the ~11yr sim stretched to ~40 min under a concurrent nightly `pg_dump` on 2026-07-07 —
batch into ONE windowed query / temp-table join to cut wall-time) ·
signals EXISTS scaling · annotator on shared Upstox limiter · graduation one-way ratchet ·
advised_lots units-vs-lots · armed heat-cap sync HTTP (memoize) · advisory heat ntfy unreachable
flag-OFF (more inert than its contract) · fast-move pre-entry window is doctrine-fidelity (both
planes matched) · Manas detail stamps best-setup pivot not fired pivot · regime gate calendar-day
window + untuned bands + thin-window confidence · Minervini screen flag no passthrough ·
.env.example missing new flags · disarm-ladder undocumented · CI shard timing tripwire ·
duplicate/degraded backtest variants · SwingRun.candidates=0 · Manas→Minervini module coupling ·
manual/other books unreachable in nav; no aggregate ledger · colour-only chips · muted/70 contrast ·
role=tab contract · sticky thead · backtest running-state poll/announce · canvas-only equity curve ·
contracts.bridge not extended · MinerviniGates no unit test · trail-ratchet/same-bar-arm tests ·
PaperMarginAnnotator test · bare /paper vs /signals default asymmetry.

### REFUTED (for the record)
- Same-bar geometry lookahead in the sims — bar-close data at an EOD decision point; live is
  *fresher*, not staler.
- Single-threaded scheduler starvation — both services run virtual-thread schedulers.
- Manas sell-decision stopLevel bar-0-ATR wrong number — renders NULL/blank, not wrong.
- "CI never builds most images" — ci-e2e compose --build builds every image on every PR.
- Batch REST fan-out pushing graduation past 21:00 — schedulers fire independently.

---

*Phase 1: 8 recon agents (~1.2M tokens). Phase 2: 25 agents (~3.8M tokens, 976 tool calls).
All high/medium findings adversarially verified; severities re-graded by verifier verdicts.*

---

## 13. Fix log (2026-07-06 autonomous implementation pass)

Owner mandate: implement P0 → High → Med → Low autonomously overnight. Policy adopted:
**auto-merge** clean correctness/ops/a11y fixes on CI-green; **HOLD green-but-unmerged** any PR
that changes an owner-facing number, exit doctrine, or a parity surface (owner reviews on wake).
Every PR: build → test → adversarial review → CI-green → merge-or-hold.

### Priority (P0 — screen/bhavcopy race + swing anchor orphans) — SHIPPED + DEPLOYED + LIVE-VERIFIED
| id | fix | PR |
|---|---|---|
| P0-1 (=H1) | Minervini screen no longer races its bhavcopy — event-chain'd off the bhavcopy completion | merged |
| P0-2 (=H2) | Both swing engines adopt orphaned/superseded-version anchors (strategy-keyed) so open positions keep stop evaluation + stay in sell-decisions | merged |
| P0-3 | Loud exit-pass on candle-fetch failure (retry + `exitSkipped` counter, no silent skip) | merged |
| P0-4 (=H10) | EOD-batch failure ntfy + `swing_batch_runs` did-not-run canary (V025) | merged |

### High — DONE
| # | fix | PR |
|---|---|---|
| H3 | Per-entry risk gate inside the candidate loop (YAML `max_positions` now enforced live, not once-per-batch) | [#590] |
| H7 | Reset-ledger one-click now `window.confirm`-gated (names the book) + disabled while pending | [#592] |
| H9 | Stale-jar deploy guard: every service jar bakes its git sha (`git.properties`) + build time (`build-info.properties`) → surfaced at `/actuator/info`; `ay verify-deploy` compares each running container's sha to source HEAD and fails loud on a STALE jar (the mutable `:dev` trap) | [#617] |

### Medium — MERGED (auto-merge tier)
| # | fix | PR |
|---|---|---|
| M12 | RS tie-rank deterministic (stable `ORDER BY` secondary key) | [#591] · HOLD-for-review |
| M35 | Manas liquidity depth 25×→50× (doc-faithful, config-tunable) | [#591] · HOLD-for-review |
| M39 | VCP detector base-depth (≥60% reject) + 3–65wk duration caps | [#591] · HOLD-for-review |
| M19 | Kill-switch labelled per-book (was misleading "Master"); tooltips name the book + point to Telegram /pause | [#592] |
| M21 | Graduation Win% renders as a percent, not the raw `0.7500` fraction | [#592] |
| M24 | Telegram bot token redacted from WARN logs on transport failures | [#593] |
| M26 | Both swing backtest daemons: `Error` → failed report + ntfy (was stuck "running" forever) | [#593] |
| M34 | RiskService trip-dedup delimiter unified (was mixed space/NUL → heat-cap never re-armed); removed 2 committed NUL bytes | [#593] |
| M25 | Single-permit `SwingBacktestGate` — two deep sims can't OOM the 448 MB live heap | [#595] |
| M22 | Screener Recompute invalidates the derived funnel + candidate caches | [#596] |
| M23 | Clickable table rows keep `<tr>` grid semantics (in-cell toggle button; dropped `role="button"`) | [#596] |

### HELD green-unmerged — owner reviews on wake (changes an owner-facing number / doctrine)
| # | fix | PR | why held |
|---|---|---|---|
| M32 | Corrected the stale Manas exit-doctrine comment (live IS 2×ATR, not an sma20 proxy — since #573) | [#594] | doctrine text |
| M33 | Manas sell-decision reports the real 2×ATR trail via a new parity-safe `ExitEvaluator.trailStop` accessor (was reporting sma20) | [#594] | changes the daily sell-decision "trail level" number |
| M12/M35/M39 | (above) | [#591] | screener selection change — owner eyeballs the new pass-set |

### Remaining (NOT done — reason)
- **High:** **H4** (Manas live ATR-trail semantics vs backtest — arm-anchor/ATR-pinning/breakeven-floor; the deeper parity fix — #594 only corrected the sell-decision *report*, not the exit *engine*), **H5** (graduation PnL cross-attribution — needs a `position_id` FK migration), **H6** (screener reads CA-unadjusted bhavcopy), **H8** (cheat-3c mislabel — doctrine), **H11** (Manas swing engine/sell-decision/scheduler zero tests), **H12** (per-book paper isolation untested). *(H9 stale-jar guard DONE — [#617].)*
- **Medium:** M1 (margin-heat `?book=` — needs a contract recapture), M2 (reconcile transient-retry — first attempt broke `#579` IT; needs the resolver to signal transient-vs-stable), M3/M4/M6/M7/M8/M9/M10/M11/M27 (swing exit-parity + backtest-methodology — a coherent HOLD batch), M13/M14 (holiday-guard / freshness — entangled with the owner's "analyse the last close any session" preference), M15 (historical, doc-only), M16 (book-DEFAULT fail-open — first attempt broke `PaperAccountRiskIntegrationTest`; needs a Books-scoped startup assertion not a runtime guard), M17/M18/M20 (larger FE surfaces), M29/M30 (ops-knob exposure / backup-before-migration convention), M31 (fork-debt refactor — high blast radius), M36/M37/M38/M40 (setup-doctrine — owner calls).
- **Low:** ~28 items — none started this pass (all cosmetic / long-tail / test-nicety).

*Fix pass authored autonomously 2026-07-06; PR links are on the repo. HELD PRs await the owner's
sign-off before merge.*
