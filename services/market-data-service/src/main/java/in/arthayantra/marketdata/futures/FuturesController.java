package in.arthayantra.marketdata.futures;

import in.arthayantra.marketdata.freshness.DataFreshness;
import java.time.Clock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Phase-15A futures surface (B-18). */
@RestController
@RequestMapping("/api/v1/market/futures")
public class FuturesController {

  private final FuturesTermStructureService termStructureService;
  private final Clock clock;

  /** Wires the read path. */
  public FuturesController(FuturesTermStructureService termStructureService, Clock clock) {
    this.termStructureService = termStructureService;
    this.clock = clock;
  }

  /** Near/next/far + spot, basis, contango state, calendar spread — one batched quote. */
  @GetMapping("/term-structure")
  public FuturesTermStructureService.TermStructure termStructure(@RequestParam String underlying) {
    FuturesTermStructureService.TermStructure ts = termStructureService.termStructure(underlying);
    // Live batched quote; complete unless serving the last-good structure off-hours / on quote failure.
    return ts.withFreshness(DataFreshness.of(ts.asOf(), DataFreshness.LIVE, "capture", null, !ts.stale(), clock));
  }
}
