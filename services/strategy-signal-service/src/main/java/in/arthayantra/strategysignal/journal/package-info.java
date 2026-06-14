/**
 * Trade-journal module (F-44A / FP-66): note + tags + discipline/emotion ratings with nullable
 * same-schema links to signals / paper positions and SOFT references to backtest runs/trades (never
 * cross-schema FKs). CRUD under {@code /api/v1/journal/**}; the {@code /journal} weekly-review route
 * and the JournalDrawer consume it. Screenshots are out of v1.
 */
package in.arthayantra.strategysignal.journal;
