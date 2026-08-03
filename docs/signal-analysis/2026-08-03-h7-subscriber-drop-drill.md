# H7 — mid-session subscriber-drop drill, 2026-08-03 (live, market hours)

**Verdict: the drill RAN and the result is a negative one about the drill, not about the code — a TCP
connection drop self-heals in ~22 ms and therefore CANNOT reach `SubscriberHealthCanary`'s 180 s
threshold. `computed`. So this injection does not exercise the watchdog's fire path, and it does not
reproduce the 2026-07-07 incident. F10 Part B is NOT overturned; but the mid-session-drop class
remains live-unexercised, and now we know why: the obvious way to inject it is self-healing.**

Owner authorised the drill on live during market hours (2026-08-03). Blast radius was re-confirmed
with the owner before firing, because it is **global, not per-symbol** — see §2.

## 1. What was run

| | |
|---|---|
| Injection | `docker exec ay-redis redis-cli -n 0 CLIENT KILL ID 983` |
| Target | the engine's single Redis pub/sub connection (`flags=P`, `sub=3`) held by strategy-signal-service (172.18.0.11) |
| T0 (kill) | **12:43:34 IST** |
| Watchdog state | ARMED — `ARTHA_SIGNALS_SUBSCRIBER_WATCHDOG_ENABLED=true`, `BAR_GAP_MS=180000`, `FEED_FRESH_MS=90000` `sourced` (container env) |
| Pre-state | `bar_evaluated_age 13.3 s`, `bar_received_age 24.2 s`; `subscriber_health_events` = **10 rows** `computed` |

## 2. The drill cannot be scoped to one symbol — this corrects the row's framing

`SubscriberHealthCanary`'s own javadoc states it, and `CLIENT LIST` confirms it live: the engine
subscribes every candle channel through a **single** `RedisMessageListenerContainer` on **one**
connection, so Redis pub/sub multiplexes all channels over that one subscription. Observed:
one connection, `sub=3`. A connection drop therefore takes down **all** channels together —
never one in isolation. The 2026-07-07 incident agrees empirically (NIFTY and SENSEX evaluation
both stopped at the same instant, 14:22:45).

Any future statement of this drill that says "scoped to one symbol" is wrong by construction.

## 3. What happened

`sourced` — strategy-signal-service logs, timestamps UTC in the log, IST in brackets:

```
07:13:35.165Z [12:43:35.165 IST] INFO io.lettuce.core.protocol.ConnectionWatchdog
    Reconnecting, last destination was redis/172.18.0.2:6379
07:13:35.187Z [12:43:35.187 IST] INFO io.lettuce.core.protocol.ReconnectionHandler
    Reconnected to redis/<unresolved>:6379
```

- Reconnect began **1.2 s** after the kill and completed **22 ms** later.
- A fresh pub/sub connection (`id=223261`, `flags=P`, `sub=3`) was present at T0+3 s — same three
  channels, same client. `computed`
- Bar receipt never gapped. Four polls after the kill: `received_age` 60.0 → 25.6 → 51.6 → 17.7 s,
  with `evaluated_age` tracking it to within 10 ms. That is the normal 1-minute bar sawtooth.
  `computed`
- `subscriber_health_events` **unchanged at 10 rows**. `computed`

## 4. Reading the result

**The watchdog behaved correctly by staying silent.** Its trigger is "no candle received for
`bar-gap-ms` (180 s) WHILE the tick feed is provably fresh". The gap here was ~1.2 s. Firing would
have been a false positive.

**The important inference is about 2026-07-07.** That incident produced a multi-minute silence.
A TCP-level connection drop cannot produce one — Lettuce's `ConnectionWatchdog` repairs it three
orders of magnitude faster than the detection threshold. Therefore the 07-07 failure was **not** a
connection drop in the transport sense. It has to be a *silent* subscription loss where the
connection itself stays up and healthy — the listener registry going away, or the container losing
its subscriptions without the socket closing. Lettuce sees nothing to reconnect, so nothing is
repaired, and only the 180 s bar-gap watchdog can notice.

That shape **cannot be injected from outside Redis**, which is why this drill could not exercise the
fire path. `CLIENT KILL` tests the one class that was never in doubt.

## 5. What this does and does not close

- **Does close:** the question "does killing the engine's Redis subscription mid-session cause an
  outage?" Answer: no — it self-heals in ~22 ms with zero missed bars, verified on a live tape.
- **Does close:** the row's "one symbol" framing, which was wrong.
- **Does NOT close:** whether `SubscriberHealthCanary` actually fires, re-subscribes and pages
  against a *real* silent drop on live. Its fire path is still covered only by unit tests
  (`SubscriberHealthCanaryTest`). Exercising it for real needs an in-process fault injection —
  e.g. a test-only endpoint that stops the listener container without closing the connection —
  which is a build, not a drill.
- **Does NOT overturn** F10 Part B. The original prose warned a negative result would; this is not
  that negative result, because the injection never reached the detector.

## 6. Open doubts

1. **One injection, one session.** A single `CLIENT KILL` at 12:43 IST on a healthy stack. I did not
   repeat it, nor try it under load or near a bar boundary.
2. **I did not prove the 07-07 mechanism** — only that it was not a transport-level drop. The
   listener-registry hypothesis is `assumed`, inferred from the reconnect timing, not observed.
3. **`ay_signal_bar_received_age_seconds` read 24.2 s while `evaluated` read 13.3 s** in the pre-state
   — evaluated fresher than received, which should be impossible if evaluation follows receipt.
   Post-drill the two track to within 10 ms, so this is most likely a scrape-ordering artifact
   between two gauges sampled at different instants. Not chased; flagged because if it is not an
   artifact, the receipt-to-eval lag comparison the canary relies on has a measurement error in it.
4. **No ntfy page was expected or observed**, consistent with the watchdog not firing — but that
   means this drill also did not verify the alerting leg end to end.
