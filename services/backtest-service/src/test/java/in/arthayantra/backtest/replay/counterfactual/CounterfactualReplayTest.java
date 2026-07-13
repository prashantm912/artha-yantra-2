package in.arthayantra.backtest.replay.counterfactual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.dispatch.JobStreamDispatcher;
import in.arthayantra.backtest.dispatch.ProgressPublisher;
import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.jobs.JobStatus;
import in.arthayantra.backtest.replay.CandleReader;
import in.arthayantra.backtest.replay.counterfactual.CounterfactualResult.VariantResult;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit test for the counterfactual replay CORE (no DB): the same three observed entries are replayed
 * under different exit-knob variants and the per-variant outcome DIFFERS accordingly — the whole point.
 * A mocked {@link CandleReader} supplies the captured premium path; the persisted variant list is
 * captured off a mocked {@link CounterfactualRunRepository}. Also pins determinism (two runs, identical
 * numbers) and the reused {@code PremiumExitEvaluator} precedence (TP / SL / END_OF_DATA).
 */
class CounterfactualReplayTest {

  private static final String SYM = "NIFTY16JUN2625000CE";

  private static OffsetDateTime t(String hhmm) {
    return OffsetDateTime.parse("2026-06-16T" + hhmm + ":00+05:30");
  }

  private static EngineCandle bar(String hhmm, String close) {
    BigDecimal c = new BigDecimal(close);
    return new EngineCandle(t(hhmm), c, c, c, c, 0L);
  }

  /** Premium path: 100 → 112 (peak) → 90 (drop). TP@110 exits at 112; SL@95 and hold exit at 90. */
  private CounterfactualService serviceWith(CounterfactualRunRepository runs) {
    CandleReader reader = mock(CandleReader.class);
    when(reader.read(eq("NFO"), eq(SYM), eq("1m"), any(), any()))
        .thenReturn(List.of(bar("09:15", "100"), bar("09:16", "112"), bar("09:17", "90")));
    return new CounterfactualService(
        mock(JobRepository.class),
        mock(JobStreamDispatcher.class),
        mock(ProgressPublisher.class),
        mock(CapturedPremiumGuard.class),
        reader,
        runs,
        new ObjectMapper(),
        500);
  }

  private Job jobWithThreeVariants() {
    ObjectMapper om = new ObjectMapper();
    ObjectNode req = om.createObjectNode();
    req.put("counterfactual", true);
    req.put("from", "2026-06-16T00:00:00+05:30");
    req.put("to", "2026-06-17T00:00:00+05:30");
    ArrayNode entries = req.putArray("entries");
    ObjectNode e = entries.addObject();
    e.putNull("signalId");
    e.put("exchange", "NFO");
    e.put("tradingsymbol", SYM);
    e.put("entryTime", "2026-06-16T09:15:00+05:30");
    e.put("entryPremium", new BigDecimal("100"));
    e.put("qty", 150L);
    e.put("lotSize", 75);
    ArrayNode variants = req.putArray("variants");
    variant(variants, "tp10", "takeProfitPct", new BigDecimal("10"));
    variant(variants, "sl5", "stopLossPct", new BigDecimal("5"));
    variants.addObject().put("name", "hold").putObject("exitKnobs"); // no knobs → END_OF_DATA
    return new Job(
        UUID.randomUUID(), JobKind.COUNTERFACTUAL, null, JobStatus.RUNNING, 0, null, req,
        null, null, "corr", null, null, null, "owner", List.of(), null);
  }

  private static void variant(ArrayNode variants, String name, String knob, BigDecimal value) {
    ObjectNode v = variants.addObject();
    v.put("name", name);
    v.putObject("exitKnobs").put(knob, value);
  }

  @Test
  void variantsScoreTheSameEntriesUnderDifferentExitKnobs() {
    CounterfactualRunRepository runs = mock(CounterfactualRunRepository.class);
    when(runs.insert(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(UUID.randomUUID());
    CounterfactualService service = serviceWith(runs);

    service.run(jobWithThreeVariants(), pct -> {}, () -> false);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<VariantResult>> captor = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(runs)
        .insert(any(), any(), any(), org.mockito.ArgumentMatchers.eq(1), captor.capture(), eq("owner"));
    List<VariantResult> results = captor.getValue();
    assertThat(results).extracting(VariantResult::name).containsExactly("tp10", "sl5", "hold");

    VariantResult tp = byName(results, "tp10");
    VariantResult sl = byName(results, "sl5");
    VariantResult hold = byName(results, "hold");

    // TP@110 fires on the 112 bar; SL@95 and the knob-less "hold" fall to the 90 bar.
    assertThat(tp.exitReasonCounts()).containsEntry("TAKE_PROFIT", 1);
    assertThat(sl.exitReasonCounts()).containsEntry("STOP_LOSS", 1);
    assertThat(hold.exitReasonCounts()).containsEntry("END_OF_DATA", 1);

    // The take-profit variant wins (exit 112); the other two exit at 90 (loss) — knobs change outcome.
    assertThat(tp.netPnlInr()).isPositive();
    assertThat(sl.netPnlInr()).isNegative();
    assertThat(hold.netPnlInr()).isNegative();
    assertThat(tp.grossPremiumPoints()).isEqualByComparingTo("12.0000"); // 112 - 100
    assertThat(hold.grossPremiumPoints()).isEqualByComparingTo("-10.0000"); // 90 - 100
    assertThat(tp.tradeCount()).isEqualTo(1);
    assertThat(tp.winRate()).isEqualByComparingTo("1.000000");
    assertThat(hold.winRate()).isEqualByComparingTo("0.000000");
    // single losing trade → maxDD equals the loss magnitude (cost-inclusive).
    assertThat(hold.maxDrawdownInr()).isEqualByComparingTo(hold.netPnlInr().negate());
  }

  @Test
  void sameRequestProducesIdenticalNumbers() {
    CounterfactualRunRepository a = mock(CounterfactualRunRepository.class);
    when(a.insert(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(UUID.randomUUID());
    CounterfactualRunRepository b = mock(CounterfactualRunRepository.class);
    when(b.insert(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(UUID.randomUUID());

    serviceWith(a).run(jobWithThreeVariants(), pct -> {}, () -> false);
    serviceWith(b).run(jobWithThreeVariants(), pct -> {}, () -> false);

    assertThat(capture(a)).usingRecursiveComparison().isEqualTo(capture(b));
  }

  private static VariantResult byName(List<VariantResult> results, String name) {
    return results.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private static List<VariantResult> capture(CounterfactualRunRepository runs) {
    ArgumentCaptor<List<VariantResult>> captor = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(runs)
        .insert(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), captor.capture(), any());
    return captor.getValue();
  }
}
