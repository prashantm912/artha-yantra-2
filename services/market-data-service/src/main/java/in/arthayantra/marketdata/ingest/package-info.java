/**
 * The ingest-run ledger (audit 2026-07-10 §7.2.3 / §9.1): {@code marketdata.ingest_runs} plus its
 * fail-soft writer {@link in.arthayantra.marketdata.ingest.IngestRunLedger}. Every batch/ingest job
 * records which source ran, its window, outcome, row count, error, and timings here — the
 * batch-source trust oracle the ingest-health board (A11) and Prompt-2 data-trust decisions read.
 * A leaf module: it depends on nothing else in the service (jobs depend on it, never the reverse).
 */
package in.arthayantra.marketdata.ingest;
