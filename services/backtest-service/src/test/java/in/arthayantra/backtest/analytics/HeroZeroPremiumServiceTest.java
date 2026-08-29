package in.arthayantra.backtest.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import in.arthayantra.backtest.replay.CandleReader;
import in.arthayantra.backtest.replay.TradeRepository;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit cover for {@link HeroZeroPremiumService}: the hero/zero excursion classification, the S24d
 * per-strike %-change confirmation, the 50%-stop touch, and graceful degrade when a leg has no premium
 * bars. The mirror-skew DB reads are stubbed empty (the paired-contract path is exercised separately).
 */
@ExtendWith(MockitoExtension.class)
class HeroZeroPremiumServiceTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  @Mock private TradeRepository trades;
  @Mock private CandleReader candles;
  @Mock private JdbcTemplate jdbc;
  private HeroZeroPremiumService svc;

  @BeforeEach
  void setUp() {
    svc = new HeroZeroPremiumService(trades, candles, jdbc);
    // no expired_contracts metadata → mirror skew degrades to null (covered separately).
    lenient().when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
  }

  private static OffsetDateTime ts(int hh, int mm) {
    return OffsetDateTime.of(2026, 6, 26, hh, mm, 0, 0, IST); // an expiry-day afternoon
  }

  private static EngineCandle bar(OffsetDateTime t, String premium) {
    BigDecimal p = new BigDecimal(premium);
    return new EngineCandle(t, p, p, p, p, 1000L);
  }

  private static Map<String, Object> trade(
      int seq, String symbol, OffsetDateTime entry, OffsetDateTime exit, String entryPrem, String exitPrem, String sl) {
    Map<String, Object> t = new LinkedHashMap<>();
    t.put("seq", seq);
    t.put("exchange", "NFO");
    t.put("tradingsymbol", symbol);
    t.put("entryTs", entry.toString());
    t.put("exitTs", exit.toString());
    t.put("entryPrice", new BigDecimal(entryPrem));
    t.put("exitPrice", new BigDecimal(exitPrem));
    t.put("stopLoss", sl == null ? null : new BigDecimal(sl));
    t.put("pnl", new BigDecimal(exitPrem).subtract(new BigDecimal(entryPrem)));
    return t;
  }

  @Test
    void classifiesHeroZeroFlatAndConfirmsThePerStrikeMove() {
    UUID run = UUID.randomUUID();

    // HERO: entry 100, surges to a 250 peak (2.5×), exits 200; premium ran +66.7% in the 15m before entry.
    when(candles.read(eq("NFO"), eq("HERO"), eq("1m"), any(), any()))
        .thenReturn(
            List.of(
                bar(ts(14, 25), "60"), // entry-15m → confirm baseline
                bar(ts(14, 40), "100"), // entry
                bar(ts(14, 45), "180"),
                bar(ts(14, 50), "250"), // peak
                bar(ts(14, 55), "200"))); // exit
    // ZERO: entry 100 decays to a 40 exit (0.4×); flat premium into entry (not confirmed).
    when(candles.read(eq("NFO"), eq("ZERO"), eq("1m"), any(), any()))
        .thenReturn(
            List.of(
                bar(ts(14, 25), "100"),
                bar(ts(14, 40), "100"),
                bar(ts(14, 45), "70"),
                bar(ts(14, 50), "40")));
    // UNPRICED: no backfilled premium bars for the leg.
    when(candles.read(eq("NFO"), eq("NONE"), eq("1m"), any(), any())).thenReturn(List.of());

    when(trades.findByRun(eq(run), anyInt(), anyInt(), any(), any(), any()))
        .thenReturn(
            List.of(
                trade(1, "HERO", ts(14, 40), ts(14, 55), "100", "200", "50"),
                trade(2, "ZERO", ts(14, 40), ts(14, 50), "100", "40", "50"),
                trade(3, "NONE", ts(14, 40), ts(14, 50), "100", "0", "50")));

    HeroZeroPremiumService.HeroZeroPremium out = svc.diagnostics(run);

    assertThat(out.tradeCount()).isEqualTo(3);
    assertThat(out.tradesPriced()).isEqualTo(2);
    assertThat(out.tradesUnpriced()).isEqualTo(1);
    assertThat(out.heroes()).isEqualTo(1);
    assertThat(out.zeros()).isEqualTo(1);
    assertThat(out.flats()).isEqualTo(0);
    assertThat(out.confirmed()).isEqualTo(1); // only HERO ran >50% into entry

    List<HeroZeroPremiumService.HeroZeroTrade> rows = out.trades();
    HeroZeroPremiumService.HeroZeroTrade hero = rows.get(0);
    assertThat(hero.klass()).isEqualTo("HERO");
    assertThat(hero.peakMultiple()).isEqualByComparingTo("2.5");
    assertThat(hero.confirmed()).isTrue();
    assertThat(hero.premiumChangePct()).isGreaterThan(new BigDecimal("66"));
    assertThat(hero.slTouched()).isFalse();
    assertThat(hero.mirrorSkewPct()).isNull(); // no paired contract row

    HeroZeroPremiumService.HeroZeroTrade zero = rows.get(1);
    assertThat(zero.klass()).isEqualTo("ZERO");
    assertThat(zero.confirmed()).isFalse();

    HeroZeroPremiumService.HeroZeroTrade none = rows.get(2);
    assertThat(none.priced()).isEqualTo(false);
  }

  @Test
  void flagsThe50PercentStopTouch() {
    UUID run = UUID.randomUUID();
    // entry 100, trough dips to 45 (below the 50 stop), recovers to a 60 exit.
    when(candles.read(eq("NFO"), eq("SLHIT"), eq("1m"), any(), any()))
        .thenReturn(
            List.of(
                bar(ts(14, 25), "100"),
                bar(ts(14, 40), "100"),
                bar(ts(14, 45), "45"), // touches below the 50 stop
                bar(ts(14, 50), "60")));
    when(trades.findByRun(eq(run), anyInt(), anyInt(), any(), any(), any()))
        .thenReturn(List.of(trade(1, "SLHIT", ts(14, 40), ts(14, 50), "100", "60", "50")));

    HeroZeroPremiumService.HeroZeroPremium out = svc.diagnostics(run);
    assertThat(out.slTouched()).isEqualTo(1);
  }

  @Test
    void rejectsAStaleConfirmBaselineOnASparseSeries() {
    UUID run = UUID.randomUUID();
    // bars only at entry-30m and entry — NO bar near entry-15m, so the floor lookup would otherwise
    // grab the 30m-old bar and compute a wrong-window %-change. The window+grace guard must reject it.
    when(candles.read(eq("NFO"), eq("SPARSE"), eq("1m"), any(), any()))
        .thenReturn(List.of(bar(ts(14, 10), "60"), bar(ts(14, 40), "100"), bar(ts(14, 50), "120")));
    when(trades.findByRun(eq(run), anyInt(), anyInt(), any(), any(), any()))
        .thenReturn(List.of(trade(1, "SPARSE", ts(14, 40), ts(14, 50), "100", "120", "50")));

    HeroZeroPremiumService.HeroZeroPremium out = svc.diagnostics(run);

    assertThat(out.tradesPriced()).isEqualTo(1);
    assertThat(out.premiumChangePctCount()).isEqualTo(0); // no valid baseline → excluded from the avg
    assertThat(out.confirmed()).isEqualTo(0);
    assertThat(out.avgPremiumChangePct()).isNull();
    HeroZeroPremiumService.HeroZeroTrade r = out.trades().get(0);
    assertThat(r.premiumChangePct()).isNull();
    assertThat(r.confirmed()).isFalse();
  }

  @Test
    void computesMirrorSkewFromThePairedContract() {
    UUID run = UUID.randomUUID();
    // PE leg @100; the mirror CE @80 at entry → the traded (PE) side is the richer leg, skew +25%.
    when(candles.read(eq("NFO"), eq("PE22500"), eq("1m"), any(), any()))
        .thenReturn(List.of(bar(ts(14, 25), "80"), bar(ts(14, 40), "100"), bar(ts(14, 50), "110")));
    when(candles.read(eq("NFO"), eq("CE22500"), eq("1m"), any(), any()))
        .thenReturn(List.of(bar(ts(14, 40), "80")));
    // expired_contracts: the PE leg's meta, then its CE mirror's tradingsymbol.
    when(jdbc.query(startsWith("SELECT underlying_symbol"), any(RowMapper.class), any(), any()))
        .thenReturn(
            List.of(
                new HeroZeroPremiumService.ContractMeta(
                    "NIFTY 50", LocalDate.of(2026, 6, 26), new BigDecimal("22500"), "PE")));
    when(jdbc.query(startsWith("SELECT tradingsymbol"), any(RowMapper.class), any(), any(), any(), any(), any()))
        .thenReturn(List.of("CE22500"));
    when(trades.findByRun(eq(run), anyInt(), anyInt(), any(), any(), any()))
        .thenReturn(List.of(trade(1, "PE22500", ts(14, 40), ts(14, 50), "100", "110", "50")));

    HeroZeroPremiumService.HeroZeroPremium out = svc.diagnostics(run);

    assertThat(out.tradedSideRicherCount()).isEqualTo(1);
    HeroZeroPremiumService.HeroZeroTrade r = out.trades().get(0);
    assertThat(r.mirrorPremium()).isEqualByComparingTo("80");
    assertThat(r.mirrorSkewPct()).isEqualByComparingTo("25.00");
    assertThat(r.tradedSideRicher()).isTrue();
    assertThat(r.optionType()).isEqualTo("PE");
  }

  @Test
  void emptyWhenRunHasNoTrades() {
    UUID run = UUID.randomUUID();
    when(trades.findByRun(eq(run), anyInt(), anyInt(), any(), any(), any())).thenReturn(List.of());
    HeroZeroPremiumService.HeroZeroPremium out = svc.diagnostics(run);
    assertThat(out.tradeCount()).isEqualTo(0);
    assertThat(out.trades()).isEqualTo(List.of());
  }
}
