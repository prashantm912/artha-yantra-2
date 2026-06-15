package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.marketdata.options.OiInterpretation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/** Primitive #3: per-strike buildup classification + OI-spurt %. */
@Service
public class OiSpurtService {

  public record SpurtRow(OiInterpretation interpretation, BigDecimal spurtPct) {}

  /** priorOi = oi before this interval's change (= currentOi - oiChange). */
  public SpurtRow classify(BigDecimal ltpDelta, long oiChange, long priorOi) {
    OiInterpretation interp = OiInterpretation.classify(ltpDelta, oiChange);
    BigDecimal spurt =
        priorOi == 0
            ? null
            : BigDecimal.valueOf(oiChange)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(priorOi), 2, RoundingMode.HALF_UP);
    return new SpurtRow(interp, spurt);
  }
}
