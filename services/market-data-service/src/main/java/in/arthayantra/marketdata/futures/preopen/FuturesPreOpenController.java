package in.arthayantra.marketdata.futures.preopen;

import in.arthayantra.marketdata.futures.preopen.FuturesPreOpenScan.FuturesPreOpen;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Futures Pre-Open Market read surface (oipulse §futures/pre-open-market). One endpoint:
 *
 * <p>{@code GET /api/v1/market/futures/pre-open} → {@code {phase, preOpen, asOf, advances, declines,
 * unchanged, stocks:[PreOpenRow...], indices:[PreOpenRow...]}} — the 09:00–09:08 pre-market scan: which
 * radar stock futures + indices are advancing / declining vs the prev close, with a prev-day High/Low
 * Break badge. The FE splits each list into Advances / Declines tables by the sign of {@code change}.
 *
 * <p>Delegates entirely to {@link FuturesPreOpenService#scan()}, which never throws — when Upstox
 * analytics is off (mock / analytics disabled) it returns the empty/closed shape (phase {@code UNKNOWN},
 * no rows) and the page renders its closed state rather than a 503. Map-envelope so it does not enumerate
 * a springdoc schema. Live pre-open data is published by the exchange ~09:09; the page shows that note.
 */
@RestController
@RequestMapping("/api/v1/market/futures")
public class FuturesPreOpenController {

  private final FuturesPreOpenService service;

  public FuturesPreOpenController(FuturesPreOpenService service) {
    this.service = service;
  }

  /** The Futures Pre-Open scan envelope (empty/closed shape when Upstox analytics is off). */
  @GetMapping("/pre-open")
  public FuturesPreOpen preOpen() {
    // the scan record already carries exactly these 8 components, in this order — the pre-D3
    // Map.of was a field-for-field re-emission of it, and multi-key Map.of order is JVM-salted,
    // so returning the record directly normalises order rather than changing a fixed one.
    return service.scan();
  }
}
