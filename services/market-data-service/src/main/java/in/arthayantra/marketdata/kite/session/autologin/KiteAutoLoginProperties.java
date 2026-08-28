package in.arthayantra.marketdata.kite.session.autologin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Auto-login wire settings and credential file locations.
 *
 * <p><b>⚠️ THESE PATHS ARE NOT PART OF THE PUBLISHED KITE CONNECT API.</b> They are corroborated
 * across two independent public implementations as of 2026-08-26 — which is strong evidence, not a
 * guarantee — and Zerodha can change them without notice. Two structural mitigations follow, and
 * both are load-bearing precisely BECAUSE the contract is corroborated rather than published:
 *
 * <ul>
 *   <li><b>Every PATH is its own property</b>, so a path that moves is a config change and a
 *       restart, not a rebuild — the difference between minutes and a build-and-deploy cycle on a
 *       market morning.
 *   <li><b>⚠️ That configurability is ONLY safe because {@link LoginEndpoints} validates it.</b>
 *       This javadoc used to say a wrong path "merely 404s on Zerodha's own infrastructure" and
 *       that credentials "can never be posted somewhere unintended by a path guess". <b>Both
 *       sentences were false</b> — the values below were string-concatenated onto the origin, and
 *       a path of {@code @attacker.example/api/login} makes the pinned host USERINFO and the
 *       attacker the real host (measured: {@code URI.getHost()} returns {@code attacker.example}).
 *       A leading {@code //} does the same with no {@code @} at all. <b>Edit any path below only
 *       after reading {@link LoginEndpoints}</b>, which is what actually enforces the guarantee
 *       this file used to assert on its own.
 * </ul>
 *
 * <p>⚠️ <b>CORRECTED 2026-08-28: the authorize default pointed at the wrong host, which was worth
 * fixing on its own — but it was NOT the cause of the live failure.</b> This javadoc used to say
 * "the two hosts are genuinely different and that is not a typo", and defaulted {@code authorizeBaseUrl}
 * to {@code https://kite.trade}. The corrected default matches what Kite Connect documents and
 * what its official SDK pins, so it stands.
 *
 * <p>⚠️ <b>The host change did NOT fix the login, and saying otherwise here was wrong for
 * half a day.</b> Measured live TWICE — 2026-08-27 21:25 and again 2026-08-28 08:40 AFTER the host
 * correction shipped and was armed — both with a byte-identical
 * {@code UNEXPECTED_RESPONSE (redirect carried no request_token)}. Two identical failures across
 * a host change is what settled the mechanism: the FIRST redirect is tokenless, and reading only
 * that one can never succeed. The real fix is the bounded chain walk in
 * {@code LiveLoginWireClient.authorizeFollowingRedirects}.

 * <p>The mechanism is now SETTLED by the second failure, and the earlier cookie-scope theory is
 * dead: same-origin authorize did not help. Cross-vendor review had flagged the intermediate-redirect
 * explanation as the more direct one BEFORE the host fix shipped; it was right and the record
 * says so rather than quietly reattributing.

 * <p>{@code LoginEndpoints.PINNED_ORIGINS} now holds ONE origin. An earlier draft of this
 * javadoc justified keeping {@code kite.trade} by claiming the token exchange needed it;
 * that was false — the exchange uses {@code KiteHttpProperties.baseUrl} — and the allowlist
 * would have permitted an override that recreates this very failure.
 * <p>⚠️ <b>{@link #crossOriginCookies} is EMPTY by default and that is a security decision, not a
 * placeholder.</b> It names the cookies — by name — that may travel from the login origin to the
 * authorize origin. Forwarding the whole jar across two registrable domains was Critical 2 of the
 * cross-vendor review. If the live flow proves to need one specific cookie there, add that ONE name
 * and record why; never widen it to "whatever the jar holds". See {@link LoginCookieJar}.
 *
 * <p>Credentials follow the existing {@code KiteHttpProperties} convention exactly: a {@code *File}
 * property pointing at a Docker secret, read PER CALL, never cached in a field, never logged. The
 * owner places those files; nothing in this repo creates or contains their values.
 */
@ConfigurationProperties(prefix = "artha.kite.auto-login")
public record KiteAutoLoginProperties(
    String loginBaseUrl,
    String authorizeBaseUrl,
    String credentialPath,
    String twofaPath,
    String authorizePath,
    String userIdFile,
    String passwordFile,
    String totpSeedFile,
    Set<String> crossOriginCookies) {

  /** Defaults: the ONE Zerodha login host, the documented paths, Docker secret files. */
  public KiteAutoLoginProperties {
    loginBaseUrl = loginBaseUrl == null ? "https://kite.zerodha.com" : loginBaseUrl;
    // The endpoint Kite Connect documents and its SDK pins. A kite.trade default here produced
    // the live 2026-08-27 AUTHORIZE failure; see the class javadoc for the two candidate
    // mechanisms, neither of which is established.
    authorizeBaseUrl = authorizeBaseUrl == null ? "https://kite.zerodha.com" : authorizeBaseUrl;
    credentialPath = credentialPath == null ? "/api/login" : credentialPath;
    twofaPath = twofaPath == null ? "/api/twofa" : twofaPath;
    authorizePath = authorizePath == null ? "/connect/login" : authorizePath;
    userIdFile = userIdFile == null ? "/run/secrets/kite_user_id" : userIdFile;
    passwordFile = passwordFile == null ? "/run/secrets/kite_password" : passwordFile;
    totpSeedFile = totpSeedFile == null ? "/run/secrets/kite_totp_seed" : totpSeedFile;
    crossOriginCookies = crossOriginCookies == null ? Set.of() : Set.copyOf(crossOriginCookies);
  }

  /** The Kite user id, read fresh from its secret file. */
  public String resolveUserId() {
    return readSecret(Path.of(userIdFile));
  }

  /** The account password, read fresh from its secret file. Never retained, never logged. */
  public String resolvePassword() {
    return readSecret(Path.of(passwordFile));
  }

  /** The base32 TOTP seed, read fresh from its secret file. Never retained, never logged. */
  public String resolveTotpSeed() {
    return readSecret(Path.of(totpSeedFile));
  }

  /**
   * Reads one secret file.
   *
   * <p>⚠️ The failure message names the PATH only — never the content, and never the underlying
   * exception's message, which can echo file bytes back on a decode failure.
   */
  private static String readSecret(Path file) {
    String value;
    try {
      value = Files.readString(file).trim();
    } catch (IOException e) {
      throw new IllegalStateException("missing Kite auto-login secret file " + file);
    }
    if (value.isEmpty()) {
      throw new IllegalStateException("blank Kite auto-login secret file " + file);
    }
    return value;
  }
}
