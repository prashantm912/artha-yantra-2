package in.arthayantra.marketdata.kite;

import java.util.Optional;

/**
 * Stable key → Kite token/type resolution for live adapters. Implemented by the instruments
 * module (kite must not depend on it — direction stays instruments→kite).
 */
public interface InstrumentTokenResolver {

  /** Token + type for a stable key, when the master knows it. */
  Optional<TokenInfo> resolve(InstrumentKey key);

  /** Resolution result; {@code segment} distinguishes index instruments (B-9 packet sizes). */
  record TokenInfo(long instrumentToken, String instrumentType, String segment) {

    /** True for index instruments — they use the 28/32 B packet layouts, never 44/184 B. */
    public boolean isIndex() {
      return "INDICES".equals(segment);
    }
  }
}
