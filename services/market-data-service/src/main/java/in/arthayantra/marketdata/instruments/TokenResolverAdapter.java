package in.arthayantra.marketdata.instruments;

import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Master-backed implementation of the kite-side token resolver seam. */
@Component
public class TokenResolverAdapter implements InstrumentTokenResolver {

  private static final Logger log = LoggerFactory.getLogger(TokenResolverAdapter.class);

  /** Kite's canonical NSE tradingsymbol for a BE-series stock. */
  private static final String BE_SUFFIX = "-BE";

  private static final String NSE = "NSE";

  private final InstrumentRepository repository;
  private final Counter beFallbacks;
  private final Counter inactiveBeFallbacks;

  /** Wires the master. */
  public TokenResolverAdapter(InstrumentRepository repository, MeterRegistry meters) {
    this.repository = repository;
    this.beFallbacks =
        Counter.builder("ay_instrument_be_suffix_fallback_total")
            .description("NSE token resolutions that succeeded only via the -BE suffixed symbol")
            .register(meters);
    // ⚠️ A SECOND counter rather than a tag on the first, deliberately: the H36 half is a NEW
    // behaviour on rows that previously resolved (to a token Kite rejects), so it has to be
    // separable from the H29 half at a glance. Retagging the existing series would also have
    // changed its shape for anything already reading it.
    this.inactiveBeFallbacks =
        Counter.builder("ay_instrument_be_suffix_inactive_fallback_total")
            .description(
                "NSE token resolutions that preferred the -BE twin because the bare row was inactive")
            .register(meters);
  }

  /**
   * Resolves a stable key to its Kite token, falling back to the {@code -BE} suffixed symbol on NSE.
   *
   * <p>⚠️ <b>Why the fallback exists (ledger H29).</b> Kite's canonical NSE tradingsymbol for a
   * BE-series stock carries a {@code -BE} suffix; bhavcopy, the screeners and the swing books all
   * use the BARE symbol. So {@code marketdata.instruments} holds BOTH — a tokenless bare row and a
   * tokened {@code <SYM>-BE} row — and this resolver only ever looked up the bare one. Measured
   * 2026-08-19: 304 NSE rows carry no token, and <b>27 of them have a {@code -BE} twin that does</b>
   * (exactly the 27 still trading). The token was there the whole time.
   *
   * <p>What that cost: {@code LiveHistoricalCandleGateway} threw {@code unknown instrument NSE:<sym>}
   * and fail-softed to stale cached data, silently. {@code KANORICHEM} and {@code AUTOIND} failed
   * this way every session — and both are held by OPEN swing paper positions (13, 34, 36).
   *
   * <p>⚠️ The fallback does NOT write anything. Copying the {@code -BE} token onto the bare row was
   * considered and rejected: it would put the same {@code instrument_token} on two rows, which the
   * symbol-normalization doctrine forbids, and it would go stale the moment a symbol leaves the BE
   * series. The {@code -BE} row is already kept current by the master sync — this just reads it.
   *
   * <p>The counter is the point as much as the resolution is: the original failure was invisible
   * because it fail-softed. A silent SUCCESS would repeat that mistake, so every fallback is counted
   * and the first one per symbol is logged.
   *
   * <p>⚠️ <b>The second half (ledger H36).</b> H29 closed the tokenless case and left a wider one
   * open, because it keyed on the condition that matched the two symbols it was looking at. The
   * bare row can also carry a token that Kite <b>rejects</b> — {@code 400 … invalid token} — and
   * such a row RESOLVES, so the fallback above never fired for it. Measured 2026-08-21: <b>twelve</b>
   * NSE symbols in that state, every one {@code is_active = false} with a token. So an inactive bare
   * row now prefers its {@code -BE} twin.
   *
   * <p>⚠️ <b>Strictly additive, and that is a deliberate limit rather than an accident.</b> The twin
   * only wins when the bare row is absent, tokenless, or inactive; when there is no twin the bare
   * row's own answer is returned unchanged. NSE holds ~549 inactive-with-token rows and only 160 of
   * all inactive rows have a twin at all — returning empty for the remainder would turn a broken
   * {@code 400} into a NEW {@code 404} across a population nobody asked about, which is a wider
   * change than this fix is for. Nothing that resolved before stops resolving.
   */
  @Override
  public Optional<TokenInfo> resolve(InstrumentKey key) {
    Optional<Instrument> directRow = repository.findByKey(key.exchange(), key.tradingsymbol());
    Optional<TokenInfo> direct = directRow.flatMap(TokenResolverAdapter::token);
    if (!isBeFallbackCandidate(key)) {
      return direct;
    }
    // An ACTIVE bare row that carries a token is the answer, exactly as before. This is the branch
    // 10,205 of NSE's 11,058 rows take, and nothing below can reach them.
    boolean directIsUsable = direct.isPresent() && directRow.get().active();
    if (directIsUsable) {
      return direct;
    }
    Optional<TokenInfo> viaBe = lookup(NSE, key.tradingsymbol() + BE_SUFFIX);
    if (viaBe.isPresent()) {
      if (direct.isPresent()) {
        inactiveBeFallbacks.increment();
        log.info(
            "instrument {}:{} resolved via its {} twin — the bare row is INACTIVE and its token is"
                + " one Kite rejects (H36)",
            key.exchange(), key.tradingsymbol(), BE_SUFFIX);
      } else {
        beFallbacks.increment();
        log.info(
            "instrument {}:{} resolved via its {} twin — the bare row carries no Kite token (H29)",
            key.exchange(), key.tradingsymbol(), BE_SUFFIX);
      }
      return viaBe;
    }
    // ⚠️ `direct`, NOT empty. This is what keeps the change STRICTLY ADDITIVE: an inactive bare row
    // with no twin answers exactly what it answered before (a token Kite will reject), rather than
    // becoming a 404. Turning a broken 400 into a NEW 404 across the ~389 inactive-with-token rows
    // that have no twin would be a wider behaviour change than the one this fix is for, and on rows
    // nobody asked about.
    return direct;
  }

  /**
   * NSE only, and never for a symbol that already carries the suffix — {@code FOO-BE-BE} is not a
   * thing, and letting it be tried would turn one miss into two queries on every unresolvable name.
   * BSE has no BE series, so the suffix means nothing there.
   */
  private static boolean isBeFallbackCandidate(InstrumentKey key) {
    // ⚠️ The null check is not defensive padding — it is a REGRESSION GUARD. SubscribeRequest
    // (SubscriptionsController:23) is a bare record with no @NotBlank/@Valid, so a body with a null
    // tradingsymbol reaches here. Before this fallback existed that returned empty and the caller
    // answered a clean 404; without the check, endsWith() NPEs and it becomes a 500. Caught in
    // review, 2026-08-19.
    //
    // ⚠️ Exact-match on exchange is DELIBERATE, and must not be "fixed" to match
    // SubscriptionRegistry:184, which trims and upper-cases. Normalizing here would let the
    // fallback fire for "nse" where the DIRECT lookup cannot — i.e. the backup path would be more
    // permissive than the path it backs, which is how a fallback starts resolving things the
    // primary would refuse.
    return NSE.equals(key.exchange())
        && key.tradingsymbol() != null
        && !key.tradingsymbol().endsWith(BE_SUFFIX);
  }

  private Optional<TokenInfo> lookup(String exchange, String tradingsymbol) {
    return repository.findByKey(exchange, tradingsymbol).flatMap(TokenResolverAdapter::token);
  }

  /** A row's token, or empty when it carries none — the tokenless-bare-row case H29 is about. */
  private static Optional<TokenInfo> token(Instrument row) {
    return row.instrumentToken() == null
        ? Optional.empty()
        : Optional.of(new TokenInfo(row.instrumentToken(), row.instrumentType(), row.segment()));
  }
}
