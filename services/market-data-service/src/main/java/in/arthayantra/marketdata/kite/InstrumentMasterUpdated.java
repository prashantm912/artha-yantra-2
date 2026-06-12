package in.arthayantra.marketdata.kite;

/**
 * Published after every successful instrument sync (Phase 9 pipeline) so kite-side consumers —
 * the pinned-indices subscriber, later the futures pinner (15A) — can re-resolve instruments
 * that were unknown at startup. Declared in the kite module: instruments → kite is the allowed
 * dependency direction.
 */
public record InstrumentMasterUpdated(long activeInstruments) {}
