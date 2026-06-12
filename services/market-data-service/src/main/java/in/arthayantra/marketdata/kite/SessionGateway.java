package in.arthayantra.marketdata.kite;

/**
 * Port 1/5 (A.7a): Kite session state. Live = OAuth ritual + AES-GCM token store (Stage B,
 * Phase 12); mock = always valid. The canonical Redis status key is {@code kite:session:status}
 * (COMMON §3).
 */
public interface SessionGateway {

  /** True when market data calls may be made. */
  boolean sessionActive();

  /** The MOCK/LIVE status string written to {@code kite:session:status}. */
  String statusLabel();
}
