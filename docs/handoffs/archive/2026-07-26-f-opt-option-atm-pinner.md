# F-OPT — live intraday NFO/BFO option-premium 1m capture (`OptionAtmPinner`)

Ledger row **G3**. A NEW CAPABILITY, not a regression fix: today there is no live option-premium
1-minute series, so every premium study runs on read-time-derived history where Dow + IV factors
degrade to NEUTRAL — a data-fidelity artifact that makes the OI edge read MUTED. This closes that by
pinning near-expiry ATM±N option contracts to the live WS feed so their ticks flow through
`BarWriter` → `TICK_AGG` exactly like futures do.

Owner decision: **build AND arm.** Ship it enabled, not dormant.

## The template — copy its shape

`kite/ticker/FuturesPinner.java` (101 lines) is the exact analogue and the pattern to follow:

- `@EventListener(ApplicationReadyEvent.class)` for the initial pass, plus
  `@EventListener(InstrumentMasterUpdated.class)` so expiry rollover re-pins automatically.
- One `synchronized repin()` that computes the DESIRED set, subscribes everything in it, then
  unsubscribes anything in `currentPins` no longer desired. Rollover falls out of that reconcile for
  free — do not hand-roll roll logic.
- `registry.subscribe(SUBSCRIBER, key, SubscriptionMode.QUOTE, SubscriptionPriority.PINNED_INDEX)`
  inside a per-key try/catch that logs and continues — one unresolvable contract must never abort the
  pass.
- A `meterRegistry.gauge` over the live pin set, and a `pinnedContracts()` accessor.

Use a DISTINCT subscriber id (`FuturesPinner` uses `system-fut-pins`), or the two pinners will
unsubscribe each other's keys.

## Resolution

- Expiries: `OptionsChainService.expiriesWithin(underlying, horizonDays)` — take the NEAREST.
- Strikes: `OptionsChainService.chain(underlying, expiry)` returns `rows()` of `StrikeRow`, each with
  `.strike()`, `.ce()` and `.pe()`. Pick the ATM row and take ±N strikes around it, both CE and PE.
- Underlyings: NIFTY and SENSEX. Reuse the existing configured-underlying property style rather than
  inventing a new list format.

## Budget — the part the owner must be able to check

`SubscriptionRegistry` enforces `artha.subscriptions.cap`, **default 3000**, which matches Kite's
documented WS instrument cap. Arithmetic for the default N: 2 underlyings × (2N+1) strikes × 2 sides.
At N=5 that is 44 tokens — small against 3000, and against whatever futures/index pins already hold.

**Report, in the receipt, measured not assumed:** the pin count this produces, and the total
subscription count before and after. If the pinner would ever push the registry past its cap it must
degrade by shrinking N (or refusing to add) and LOG that loudly — it must never evict a
`PINNED_INDEX` future or a `STRATEGY` subscription to make room for a speculative option strike.

## Constraints

- `N` and the underlying list are configuration with sane defaults. Every `application.yml`
  `${ENV_NAME}` placeholder must match the compose passthrough and `.env.example` name **EXACTLY** —
  a mismatch silently swallows the override with no error (#653). Grep all three.
- Options are NFO for NIFTY and BFO for SENSEX. The canonical instrument key is
  `(exchange, tradingsymbol)` in Kite's grammar; numeric tokens are source-local session handles and
  are resolved through the master, never stored as identity.
- Market-hours gated in the same spirit as the other capture jobs — do not hold subscriptions for a
  dead session if that is what the existing pinners do; match the incumbent behaviour rather than
  inventing a new policy.
- Tests: `*Test` / `*IntegrationTest` only (no failsafe plugin; `*IT.java` is silently never run).
  Cover at minimum: the desired-set computation for a known chain, rollover (a pin no longer desired
  is unsubscribed), the cap-degradation path, and that one unresolvable contract does not abort the
  pass.
- Build with the FULL reactor + `-am`.

EDIT-ONLY: no commit, branch, push, PR, deploy or arming. The Architect owns the commit and the
arming flag.
