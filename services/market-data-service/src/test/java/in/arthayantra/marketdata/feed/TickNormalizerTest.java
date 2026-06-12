package in.arthayantra.marketdata.feed;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.instruments.InstrumentRegistry;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.RawTick;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A.7.1 normalizer contract: stable keys, exact decimals, IST offsets, monotonic seq. */
class TickNormalizerTest {

  private TickNormalizer normalizer;

  @BeforeEach
  void setUp() {
    InstrumentRegistry registry =
        InstrumentRegistry.fixedForTesting(
            Map.of(
                256265L, new InstrumentKey("NSE", "NIFTY 50"),
                100001L, new InstrumentKey("NSE", "RELIANCE")));
    normalizer = new TickNormalizer(registry);
  }

  @Test
  void resolvesTokenToStableKeyAndIstTimestamp() {
    // 2026-06-12T04:30:00Z == 10:00 IST
    RawTick raw =
        new RawTick(256265, new BigDecimal("24350.55"), 1000, Instant.parse("2026-06-12T04:30:00Z"));

    NormalizedTick tick = normalizer.normalize(raw).orElseThrow();

    assertThat(tick.exchange()).isEqualTo("NSE");
    assertThat(tick.tradingsymbol()).isEqualTo("NIFTY 50");
    assertThat(tick.lastPrice()).isEqualByComparingTo("24350.55");
    assertThat(tick.timestamp().getOffset().getId()).isEqualTo("+05:30");
    assertThat(tick.timestamp().getHour()).isEqualTo(10);
  }

  @Test
  void sequencesAreMonotonicPerInstrument() {
    for (int i = 1; i <= 3; i++) {
      NormalizedTick nifty =
          normalizer
              .normalize(new RawTick(256265, BigDecimal.ONE, i, Instant.EPOCH))
              .orElseThrow();
      assertThat(nifty.seq()).isEqualTo(i);
    }
    NormalizedTick reliance =
        normalizer.normalize(new RawTick(100001, BigDecimal.ONE, 1, Instant.EPOCH)).orElseThrow();
    assertThat(reliance.seq()).isEqualTo(1); // independent counter per instrument
  }

  @Test
  void unknownTokensAreSkipped() {
    Optional<NormalizedTick> result =
        normalizer.normalize(new RawTick(999999, BigDecimal.ONE, 1, Instant.EPOCH));

    assertThat(result).isEmpty();
  }
}
