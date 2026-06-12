package in.arthayantra.marketdata.kite;

import java.util.Optional;

/**
 * Supplies the current Kite access token to live adapters. The store-backed implementation lands
 * with the OAuth lifecycle (Phase 12); mock never has one.
 */
public interface AccessTokenProvider {

  /** The decrypted access token, when a LIVE session exists. */
  Optional<String> currentToken();
}
