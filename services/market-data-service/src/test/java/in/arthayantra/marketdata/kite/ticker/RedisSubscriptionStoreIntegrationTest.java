package in.arthayantra.marketdata.kite.ticker;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/** RedisSubscriptionStore round-trips holds through a real Redis hash (encode + parse). */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class RedisSubscriptionStoreIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired private RedisSubscriptionStore store;
  @Autowired private StringRedisTemplate redis;

  @BeforeEach
  @AfterEach
  void clearHash() {
    redis.delete(RedisSubscriptionStore.HASH);
  }

  @Test
  void roundTripsHoldsIncludingSpacedSymbols() {
    store.put(
        "ui:tab1",
        new InstrumentKey("NSE", "TCS"),
        SubscriptionMode.QUOTE,
        SubscriptionPriority.UI);
    store.put(
        "strategy:s1",
        new InstrumentKey("NSE", "NIFTY 50"),
        SubscriptionMode.FULL,
        SubscriptionPriority.STRATEGY);

    assertThat(store.all())
        .hasSize(2)
        .anySatisfy(
            h -> {
              assertThat(h.subscriber()).isEqualTo("ui:tab1");
              assertThat(h.key()).isEqualTo(new InstrumentKey("NSE", "TCS"));
              assertThat(h.mode()).isEqualTo(SubscriptionMode.QUOTE);
              assertThat(h.priority()).isEqualTo(SubscriptionPriority.UI);
            })
        .anySatisfy(h -> assertThat(h.key()).isEqualTo(new InstrumentKey("NSE", "NIFTY 50")));

    store.remove("ui:tab1", new InstrumentKey("NSE", "TCS"));

    assertThat(store.all())
        .singleElement()
        .satisfies(h -> assertThat(h.key().tradingsymbol()).isEqualTo("NIFTY 50"));
  }
}
