package in.arthayantra.marketdata.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.upstox.ExpiredBackfillRepository;
import in.arthayantra.marketdata.upstox.ExpiredBackfillRepository.ExportContract;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/** The per-expiry bulk export zips one CSV/JSON per registered contract of the (underlying, expiry). */
class BackfillExportServiceTest {

  private static final LocalDate EXPIRY = LocalDate.parse("2026-07-31");
  private static final LocalDate FROM = LocalDate.parse("2026-07-01");
  private static final LocalDate TO = LocalDate.parse("2026-07-31");

  private static ExportContract c(String symbol) {
    return new ExportContract("NFO", symbol, new BigDecimal("25000"), "CE");
  }

  @Test
  void bulkExportZipsOnePerContract() throws Exception {
    CandleRepository candles = mock(CandleRepository.class);
    ExpiredBackfillRepository repo = mock(ExpiredBackfillRepository.class);
    when(repo.contractsFor("NIFTY", EXPIRY))
        .thenReturn(List.of(c("NIFTY26JUL25000CE"), c("NIFTY26JUL25000PE")));
    when(candles.range(any(), any(), eq("1m"), any(), any())).thenReturn(List.of()); // header-only CSVs
    BackfillExportService svc = new BackfillExportService(candles, repo, new ObjectMapper());

    BackfillExportService.Export zip = svc.exportBulk("NIFTY", EXPIRY, FROM, TO, "csv");

    assertThat(zip.contentType()).isEqualTo("application/zip");
    assertThat(zip.filename()).isEqualTo("NIFTY_2026-07-31.zip");
    Set<String> entries = new LinkedHashSet<>();
    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip.body()))) {
      ZipEntry e;
      while ((e = zis.getNextEntry()) != null) {
        entries.add(e.getName());
      }
    }
    assertThat(entries).containsExactlyInAnyOrder("NIFTY26JUL25000CE.csv", "NIFTY26JUL25000PE.csv");
  }

  @Test
  void bulkExportOfAnEmptyChainIs404() {
    CandleRepository candles = mock(CandleRepository.class);
    ExpiredBackfillRepository repo = mock(ExpiredBackfillRepository.class);
    when(repo.contractsFor("NIFTY", EXPIRY)).thenReturn(List.of());
    BackfillExportService svc = new BackfillExportService(candles, repo, new ObjectMapper());

    assertThatThrownBy(() -> svc.exportBulk("NIFTY", EXPIRY, FROM, TO, "csv"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("no registered contracts");
  }
}
