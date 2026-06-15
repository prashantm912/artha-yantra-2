package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActiveStrikeServiceTest {

  private static ActiveStrikeService.StrikeOiSnap snap(
      String strike, long ceOi, long ceChg, long peOi, long peChg) {
    return new ActiveStrikeService.StrikeOiSnap(new BigDecimal(strike), ceOi, ceChg, peOi, peChg);
  }

  @Test
  void selectsTopNByTotalOi() {
    List<ActiveStrikeService.StrikeOiSnap> chain =
        List.of(
            snap("22400", 100, 0, 100, 0),
            snap("22500", 900, 0, 900, 0),
            snap("22600", 50, 0, 50, 0));
    ActiveStrikeService svc = new ActiveStrikeService(1);
    assertThat(svc.activeStrikes(chain))
        .extracting(s -> s.strike().toPlainString())
        .containsExactly("22500");
  }

  @Test
  void sentimentBullishWhenPutsBuildAndCallsUnwind() {
    // active strike 22500: PE OI building (+300), CE OI unwinding (-200); base = 1000+1000
    List<ActiveStrikeService.StrikeOiSnap> chain = List.of(snap("22500", 1000, -200, 1000, 300));
    ActiveStrikeService svc = new ActiveStrikeService(1);
    // bullishFlow = 300 - (-200) = 500; base = 2000; sentiment = 100*500/2000 = 25.00
    assertThat(svc.sentimentPct(chain)).isEqualByComparingTo("25.00");
  }

  @Test
  void sentimentNullWhenNoBaseOi() {
    assertThat(new ActiveStrikeService(5).sentimentPct(List.of())).isNull();
  }
}
