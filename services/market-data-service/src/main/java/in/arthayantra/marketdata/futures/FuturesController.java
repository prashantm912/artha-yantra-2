package in.arthayantra.marketdata.futures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Phase-15A futures surface (B-18). */
@RestController
@RequestMapping("/api/v1/market/futures")
public class FuturesController {

  private final FuturesTermStructureService termStructureService;

  /** Wires the read path. */
  public FuturesController(FuturesTermStructureService termStructureService) {
    this.termStructureService = termStructureService;
  }

  /** Near/next/far + spot, basis, contango state, calendar spread — one batched quote. */
  @GetMapping("/term-structure")
  public FuturesTermStructureService.TermStructure termStructure(@RequestParam String underlying) {
    return termStructureService.termStructure(underlying);
  }
}
