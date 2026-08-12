package in.arthayantra.marketdata.ingest;

/**
 * Published after a bhavcopy EOD backfill run completes successfully (fresh daily bars are in
 * {@code nse_eod_bhavcopy}/{@code bse_eod_bhavcopy}). Listeners: the Minervini + Manas screen
 * schedulers, which previously raced this run on an identical 19:30 IST cron — the screen keyed on
 * {@code max(trade_date)} a second before the day's rows landed, so it screened YESTERDAY and the
 * 20:00 swing batch found an empty funnel for today (2026-07-05 audit H1). The event makes the
 * chain explicit: bhavcopy completes → screens run against the fresh watermark.
 */
/*
 * WHY THIS DOES NOT LIVE WITH ITS PUBLISHER, in `bhavcopy`.
 *
 * BhavcopyCloseCanary has to hear this event: its cron fires 7 minutes after an ASYNCHRONOUS submit,
 * so a fixed minute cannot know whether the file landed. But `bhavcopy` already depends on `canary`
 * transitively, so a `canary -> bhavcopy` import closes a Modulith cycle
 * (`bhavcopy -> canary -> bhavcopy`) — which is how this was found: ModularityTest failed the
 * obvious wiring, on a full `-am verify` and nowhere earlier. `ingest` is a LEAF, importing no
 * sibling module at all, and both `bhavcopy` and `canary` already depend on it, so hosting the event
 * here adds no edge to either.
 */
public record BhavcopyBackfillCompleted(String jobId) {}
