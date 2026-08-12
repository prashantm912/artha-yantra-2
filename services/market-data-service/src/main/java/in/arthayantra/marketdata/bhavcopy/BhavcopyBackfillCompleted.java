package in.arthayantra.marketdata.bhavcopy;

/**
 * Published after a bhavcopy EOD backfill run completes successfully (fresh daily bars are in
 * {@code nse_eod_bhavcopy}/{@code bse_eod_bhavcopy}). Listeners: the Minervini + Manas screen
 * schedulers, which previously raced this run on an identical 19:30 IST cron — the screen keyed on
 * {@code max(trade_date)} a second before the day's rows landed, so it screened YESTERDAY and the
 * 20:00 swing batch found an empty funnel for today (2026-07-05 audit H1). The event makes the
 * chain explicit: bhavcopy completes → screens run against the fresh watermark.
 */
public record BhavcopyBackfillCompleted(String jobId) {}
