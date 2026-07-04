package in.arthayantra.marketdata.fundamentals;

import in.arthayantra.marketdata.upstox.UpstoxEquityMasterClient;
import in.arthayantra.marketdata.upstox.UpstoxFundamentalsClient;
import in.arthayantra.marketdata.upstox.UpstoxIncomeStatement;
import in.arthayantra.marketdata.upstox.UpstoxKeyRatios;
import in.arthayantra.marketdata.upstox.UpstoxShareHoldings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Derives + persists the per-stock Minervini fundamentals snapshot from the Upstox Company
 * Fundamentals API (ADR-0004): free-float % (100 − promoter %), market cap (P/E × net-profit,
 * both crore-consistent), free-float market cap, and ROE. The Upstox clients are optional
 * ({@code artha.upstox.analytics.enabled}); absent ⇒ {@code refresh} no-ops (mock/unentitled stacks).
 * The pure {@link #derive} does the math and is unit-tested directly with constructed DTOs.
 */
@Service
public class FundamentalsService {

  private static final Logger log = LoggerFactory.getLogger(FundamentalsService.class);

  /** Outcome of a batch refresh. */
  public record RefreshResult(int requested, int written, int skipped) {}

  private final ObjectProvider<UpstoxFundamentalsClient> fundamentals;
  private final ObjectProvider<UpstoxEquityMasterClient> master;
  private final EquityFundamentalsRepository repo;
  private final Clock clock;

  /** Wires the (optional) Upstox clients + the repository. */
  public FundamentalsService(
      ObjectProvider<UpstoxFundamentalsClient> fundamentals,
      ObjectProvider<UpstoxEquityMasterClient> master,
      EquityFundamentalsRepository repo,
      Clock clock) {
    this.fundamentals = fundamentals;
    this.master = master;
    this.repo = repo;
    this.clock = clock;
  }

  /** Whether the Upstox fundamentals feed is wired (analytics token enabled). */
  public boolean available() {
    return fundamentals.getIfAvailable() != null && master.getIfAvailable() != null;
  }

  /** Refreshes fundamentals for the given symbols (skips any the feed can't resolve). */
  public RefreshResult refresh(List<String> symbols) {
    UpstoxFundamentalsClient client = fundamentals.getIfAvailable();
    UpstoxEquityMasterClient masterClient = master.getIfAvailable();
    if (client == null || masterClient == null) {
      log.info("fundamentals refresh skipped — Upstox analytics feed not enabled");
      return new RefreshResult(symbols.size(), 0, symbols.size());
    }
    int written = 0;
    int skipped = 0;
    for (String symbol : symbols) {
      EquityFundamentals f = refreshOne(client, masterClient, symbol);
      if (f == null) {
        skipped++;
      } else {
        repo.upsert(f);
        written++;
      }
    }
    log.info("fundamentals refresh: {} written, {} skipped of {}", written, skipped, symbols.size());
    return new RefreshResult(symbols.size(), written, skipped);
  }

  private EquityFundamentals refreshOne(
      UpstoxFundamentalsClient client, UpstoxEquityMasterClient masterClient, String symbol) {
    String key = masterClient.equityKey(symbol);
    if (key == null || !key.contains("|")) {
      return null; // no Upstox NSE_EQ mapping — skip
    }
    String isin = key.substring(key.indexOf('|') + 1);
    UpstoxShareHoldings sh = client.shareHoldings(isin);
    UpstoxKeyRatios kr = client.keyRatios(isin);
    UpstoxIncomeStatement is = client.incomeStatementYearly(isin);
    if (sh == null && kr == null && is == null) {
      return null; // nothing resolved
    }
    return derive(symbol, isin, sh, kr, is);
  }

  /** Pure derivation: the three Upstox payloads → the snapshot (unit-tested). */
  EquityFundamentals derive(
      String symbol,
      String isin,
      UpstoxShareHoldings sh,
      UpstoxKeyRatios kr,
      UpstoxIncomeStatement is) {
    BigDecimal promoterPct = promoterPct(sh);
    BigDecimal freeFloatPct =
        promoterPct == null ? null : BigDecimal.valueOf(100).subtract(promoterPct);
    BigDecimal pe = ratio(kr, "p/e", "pe ratio", "price to earning");
    BigDecimal roe = ratio(kr, "roe", "return on equity");
    BigDecimal netProfitCr = incomeLatest(is, "net_profit");
    BigDecimal revenueCr = incomeLatest(is, "revenue");
    BigDecimal revenuePrevCr = incomePrev(is, "revenue");

    BigDecimal marketCapCr = null;
    if (pe != null && netProfitCr != null && netProfitCr.signum() > 0) {
      marketCapCr = pe.multiply(netProfitCr).setScale(2, RoundingMode.HALF_UP);
    }
    BigDecimal freeFloatMcapCr = null;
    if (marketCapCr != null && freeFloatPct != null) {
      freeFloatMcapCr =
          marketCapCr.multiply(freeFloatPct).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }
    return new EquityFundamentals(
        symbol, isin, marketCapCr, freeFloatMcapCr, freeFloatPct, promoterPct, pe, roe,
        netProfitCr, revenueCr, revenuePrevCr, LocalDate.now(clock));
  }

  private static BigDecimal promoterPct(UpstoxShareHoldings sh) {
    if (sh == null || sh.data() == null) {
      return null;
    }
    return sh.data().stream()
        .filter(c -> c.category() != null && c.category().toLowerCase().contains("promoter"))
        .map(c -> latest(c.history()))
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private static BigDecimal latest(List<UpstoxShareHoldings.Point> history) {
    if (history == null || history.isEmpty()) {
      return null;
    }
    for (int i = history.size() - 1; i >= 0; i--) {
      if (history.get(i) != null && history.get(i).value() != null) {
        return history.get(i).value();
      }
    }
    return null;
  }

  private static BigDecimal ratio(UpstoxKeyRatios kr, String... nameMatches) {
    if (kr == null || kr.data() == null) {
      return null;
    }
    for (UpstoxKeyRatios.Ratio r : kr.data()) {
      if (r.name() == null) {
        continue;
      }
      String n = r.name().toLowerCase();
      for (String m : nameMatches) {
        if (n.contains(m)) {
          return num(r.companyValue());
        }
      }
    }
    return null;
  }

  private static BigDecimal incomeLatest(UpstoxIncomeStatement is, String category) {
    List<UpstoxIncomeStatement.Point> h = categoryHistory(is, category);
    if (h == null || h.isEmpty()) {
      return null;
    }
    for (int i = h.size() - 1; i >= 0; i--) {
      if (h.get(i) != null && h.get(i).value() != null) {
        return h.get(i).value();
      }
    }
    return null;
  }

  private static BigDecimal incomePrev(UpstoxIncomeStatement is, String category) {
    List<UpstoxIncomeStatement.Point> h = categoryHistory(is, category);
    if (h == null || h.size() < 2) {
      return null;
    }
    int seen = 0;
    for (int i = h.size() - 1; i >= 0; i--) {
      if (h.get(i) != null && h.get(i).value() != null && ++seen == 2) {
        return h.get(i).value();
      }
    }
    return null;
  }

  private static List<UpstoxIncomeStatement.Point> categoryHistory(
      UpstoxIncomeStatement is, String category) {
    if (is == null || is.data() == null || is.data().incomeStatement() == null) {
      return null;
    }
    return is.data().incomeStatement().stream()
        .filter(l -> l.category() != null && l.category().equalsIgnoreCase(category))
        .map(UpstoxIncomeStatement.Line::history)
        .findFirst()
        .orElse(null);
  }

  /** Lenient numeric parse: strip everything except digits, sign, and the decimal point. */
  private static BigDecimal num(String s) {
    if (s == null) {
      return null;
    }
    String cleaned = s.replaceAll("[^0-9.\\-]", "");
    if (cleaned.isBlank() || cleaned.equals("-") || cleaned.equals(".")) {
      return null;
    }
    try {
      return new BigDecimal(cleaned);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
