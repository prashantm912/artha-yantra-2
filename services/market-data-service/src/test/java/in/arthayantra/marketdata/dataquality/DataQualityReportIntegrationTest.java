package in.arthayantra.marketdata.dataquality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.candles.TradingBuckets;
import in.arthayantra.marketdata.dataquality.DataQualityRepository.DataQualityRow;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Acceptance IT for the nightly per-symbol completeness artifact (FID P2-4 / audit D8). */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false",
      "artha.data-quality.enabled=true",
      "artha.options.snapshot-underlyings=DQ-CHAIN-2198",
      "artha.data-quality.chain-expected-passes=2",
      "artha.data-quality.chain-min-coverage-pct=80",
      "artha.data-quality.intraday-1m-instruments=NSE:DQ-IDX-2198",
      "artha.data-quality.intraday-min-coverage-pct=100",
      "artha.data-quality.bhavcopy-min-ratio=0.98"
    })
@AutoConfigureMockMvc
class DataQualityReportIntegrationTest extends MarketDataIntegrationTestBase {

  private static final LocalDate PRIOR = LocalDate.of(2198, 7, 13);
  private static final LocalDate DAY = LocalDate.of(2198, 7, 16);
  private static final String INDEX = "DQ-IDX-2198";
  private static final String CHAIN = "DQ-CHAIN-2198";
  private static final String KEPT_EQ = "DQKEEP2198";
  private static final String DROPPED_EQ = "DQDROP2198";

  @Autowired JdbcTemplate jdbc;
  @Autowired TradingBuckets buckets;
  @Autowired DataQualityEodJob job;
  @Autowired DataQualityRepository repository;
  @Autowired MockMvc mockMvc;

  @BeforeEach
  void seedGaps() {
    purge();
    seedBhavcopy(PRIOR, KEPT_EQ);
    seedBhavcopy(PRIOR, DROPPED_EQ);
    seedBhavcopy(DAY, KEPT_EQ);

    OffsetDateTime from = DAY.atTime(9, 15).atOffset(Ist.OFFSET);
    OffsetDateTime to = DAY.atTime(15, 30).atOffset(Ist.OFFSET);
    List<OffsetDateTime> expected = buckets.minuteBuckets(from, to);
    assertThat(expected).hasSize(375);
    List<Object[]> present =
        expected.stream()
            .filter(bucket -> !bucket.equals(DAY.atTime(11, 32).atOffset(Ist.OFFSET)))
            .map(
                bucket ->
                    new Object[] {
                      INDEX,
                      Timestamp.from(bucket.toInstant()),
                      BigDecimal.valueOf(100),
                      BigDecimal.valueOf(100),
                      BigDecimal.valueOf(100),
                      BigDecimal.valueOf(100)
                    })
            .toList();
    jdbc.batchUpdate(
        "INSERT INTO candles"
            + " (exchange,tradingsymbol,interval,bucket,open,high,low,close,volume,source)"
            + " VALUES ('NSE',?,'1m',?,?,?,?,?,0,'MOCK') ON CONFLICT DO NOTHING",
        present);

    jdbc.update(
        "INSERT INTO options_chain_snapshots"
            + " (ts,underlying,expiry,strike,option_type,tradingsymbol)"
            + " VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
        Timestamp.from(DAY.atTime(10, 0).atOffset(Ist.OFFSET).toInstant()),
        CHAIN,
        java.sql.Date.valueOf(DAY.plusDays(10)),
        new BigDecimal("25000.00"),
        "CE",
        "DQCHAIN2198CE");
  }

  @AfterEach
  void clean() {
    purge();
  }

  @Test
  void seededIntradayGapAndBhavcopyDropoutAppearInPersistedReport() throws Exception {
    int ledgerRowsBefore = dataQualityLedgerRows();
    job.scheduled();

    List<DataQualityRow> rows = repository.findByDate(DAY);
    assertThat(rows).hasSize(4);

    DataQualityRow intraday = row(rows, "intraday_1m", INDEX);
    assertThat(intraday.expected()).isEqualTo(375);
    assertThat(intraday.present()).isEqualTo(374);
    assertThat(intraday.coveragePct()).isEqualByComparingTo("99.73");
    assertThat(intraday.ok()).isFalse();

    DataQualityRow dropout = row(rows, "bhavcopy_eq", DROPPED_EQ);
    assertThat(dropout.expected()).isEqualTo(1);
    assertThat(dropout.present()).isZero();
    assertThat(dropout.ok()).isFalse();
    assertThat(dropout.detail()).isEqualTo("absent vs prior day 2198-07-13");

    DataQualityRow summary = row(rows, "bhavcopy_eq", "__SUMMARY__");
    assertThat(summary.expected()).isEqualTo(2);
    assertThat(summary.present()).isEqualTo(1);
    assertThat(summary.coveragePct()).isEqualByComparingTo("50.00");
    assertThat(summary.ok()).isFalse();

    DataQualityRow chain = row(rows, "chain_capture", CHAIN);
    assertThat(chain.expected()).isEqualTo(2);
    assertThat(chain.present()).isEqualTo(1);
    assertThat(chain.coveragePct()).isEqualByComparingTo("50.00");
    assertThat(chain.ok()).isFalse();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ingest_runs WHERE source='DATA_QUALITY'"
                    + " AND status='SUCCESS' AND rows_written=4",
                Integer.class))
        .isGreaterThanOrEqualTo(1);
    assertThat(dataQualityLedgerRows()).isEqualTo(ledgerRowsBefore + 1);

    mockMvc
        .perform(get("/api/v1/market/health/completeness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.date").value("2198-07-16"))
        .andExpect(jsonPath("$.items.length()").value(4));
    mockMvc
        .perform(get("/api/v1/market/health/completeness").param("date", "2198-07-16"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.scope == 'intraday_1m')].present").value(374))
        .andExpect(jsonPath("$.items[?(@.symbol == 'DQDROP2198')].ok").value(false));

    int ledgerRowsAfterFirstRun = dataQualityLedgerRows();
    job.scheduled();
    assertThat(repository.findByDate(DAY)).hasSize(4);
    assertThat(dataQualityLedgerRows()).isEqualTo(ledgerRowsAfterFirstRun);
  }

  private static DataQualityRow row(List<DataQualityRow> rows, String scope, String symbol) {
    return rows.stream()
        .filter(row -> row.scope().equals(scope) && row.symbol().equals(symbol))
        .findFirst()
        .orElseThrow();
  }

  private void seedBhavcopy(LocalDate date, String symbol) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy (trade_date,symbol,series)"
            + " VALUES (?,?,'EQ') ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(date),
        symbol);
  }

  private void purge() {
    jdbc.update("DELETE FROM data_quality_days WHERE trading_date IN (?,?)", PRIOR, DAY);
    jdbc.update("DELETE FROM candles WHERE exchange='NSE' AND tradingsymbol=?", INDEX);
    jdbc.update("DELETE FROM options_chain_snapshots WHERE underlying=?", CHAIN);
    jdbc.update(
        "DELETE FROM nse_eod_bhavcopy WHERE symbol IN (?,?)", KEPT_EQ, DROPPED_EQ);
  }

  private int dataQualityLedgerRows() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ingest_runs WHERE source='DATA_QUALITY'", Integer.class);
  }
}
