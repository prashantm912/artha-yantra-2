/**
 * The two-way Telegram command surface (roadmap F6): the owner's away-from-desk lever over the
 * PAPER stack only — read commands plus confirm-guarded /pause /resume /flatten that reuse the
 * EXISTING risk kill switch and mark-to-close code paths (this module adds no trading behaviour of
 * its own, and no live-broker anything). Dormant unless the commands flag, the shared bot token
 * and an allowlisted chat id are all configured; unknown senders are ignored silently; accepted
 * commands audit to {@code strategy.bot_commands}.
 */
package in.arthayantra.strategysignal.telegram;
