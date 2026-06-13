/**
 * Notifier module (Phase 41 / Q6 / E-14): subscribes in-process to {@link
 * in.arthayantra.strategysignal.signals.SignalEmitted} and pushes opted-in strategies' signals to
 * ntfy (PRIMARY, own random-suffixed topic) / Telegram (ALTERNATIVE, plain HTTPS POST — no bot
 * SDK), with per-strategy cooldown/dedup + a global hourly cap (one "N suppressed" summary), bounded
 * async retry, and a {@code notification_events} delivery audit. Opt-in lives on the strategies row
 * (Phase 21 columns), so toggling never mints a version or perturbs a D18 checksum. Payload =
 * direction/entry/SL/target/composite-vs-threshold — never credentials.
 */
package in.arthayantra.strategysignal.notifier;
