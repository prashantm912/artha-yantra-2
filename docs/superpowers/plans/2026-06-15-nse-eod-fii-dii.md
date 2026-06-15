# NSE EOD Ingestion — FII/DII Cash (first slice) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or superpowers:executing-plans. Steps use `- [ ]`.

**Goal:** Ingest NSE's daily FII/DII cash-market buy/sell/net activity into `marketdata.nse_eod_fii_dii`, and in doing so build the **reusable, anti-bot-aware NSE HTTP client** every later NSE-EOD source (participant-OI, delivery, bhavcopy) will reuse — proving a server-side NSE fetch works from the container.

**Architecture:** Mirror the existing `constituents` fetcher pattern (interface + Live/Mock impls + repository + accrual step). A new `nse` package holds: an `NseHttpClient` (RestClient with a browser User-Agent + cookie warm-up, profile-agnostic), an `FiiDiiFetcher` port (Live parses NSE JSON; Mock returns a fixture), an `NseEodFiiDiiRepository` (idempotent upsert by `(date, category)`), and a daily `NseEodScheduler` (cron after close → fetch → persist). Plain daily table (not a hypertable — 2 rows/day).

**Tech Stack:** Java/Spring `RestClient` · Jackson · TimescaleDB plain table + Flyway (marketdata, next = V012) · `MarketCalendar` (skip non-trading days).

**Source (spike-verified 2026-06-15):**
- `GET https://www.nseindia.com/api/fiidiiTradeReact` with header `User-Agent: <browser>` → `200`
- Body: `[{"category":"DII","buyValue":"18877.03","sellValue":"13535.74","netValue":"5341.29","date":"12-Jun-2026"}, {"category":"FII/FPI", ...}]` (values are ₹-crore strings; 2 rows; `date` = `dd-MMM-yyyy`).
- Homepage `GET https://www.nseindia.com/` 403s but is not required for this endpoint. Client warms cookies best-effort (ignore 403) for robustness on other endpoints later.

---

## File Structure

| File | Responsibility |
|---|---|
| `deploy/flyway/marketdata/V012__nse_eod_fii_dii.sql` (create) | `nse_eod_fii_dii` plain table |
| `.../nse/NseHttpClient.java` (create) | RestClient + browser UA + best-effort cookie warm; `getJson(path)` / `getText(url)` |
| `.../nse/FiiDiiFetcher.java` (create) | port: `List<FiiDiiRow> fetchLatest()` |
| `.../nse/LiveFiiDiiFetcher.java` (create, `@Profile("live")`) | parse the NSE JSON |
| `.../nse/MockFiiDiiFetcher.java` (create, `@Profile("!live")`) | fixture row(s) |
| `.../nse/NseEodFiiDiiRepository.java` (create) | upsert by `(date, category)` |
| `.../nse/NseEodScheduler.java` (create) | daily cron → fetch → persist |
| `.../nse/NseFiiDiiController.java` (create) | `GET /api/v1/fii-dii/cash` read endpoint |
| test: `.../nse/LiveFiiDiiFetcherTest.java` (create) | TDD JSON parse |
| `application.yml` (modify) | live NSE base URL + cron |

---

## Task 1: V012 migration

**Files:** Create `deploy/flyway/marketdata/V012__nse_eod_fii_dii.sql`

- [ ] **Step 1: Write**

```sql
-- Phase F / oipulse 1b: NSE daily FII/DII cash buy/sell/net. Plain table (2 rows/day). A2 keep.
CREATE TABLE nse_eod_fii_dii (
    trade_date  DATE          NOT NULL,
    category    TEXT          NOT NULL,           -- 'DII' | 'FII/FPI'
    buy_value   NUMERIC(18,2),
    sell_value  NUMERIC(18,2),
    net_value   NUMERIC(18,2),
    fetched_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (trade_date, category)
);
```

- [ ] **Step 2: Validate** — run any market-data IntegrationTest; expect Flyway validates incl. V012, BUILD pass.
- [ ] **Step 3: Commit** — `git commit -m "feat(market-data): nse_eod_fii_dii table (V012)"`

---

## Task 2: `NseHttpClient` — anti-bot-aware fetch

**Files:** Create `.../nse/NseHttpClient.java`

- [ ] **Step 1: Write** (browser UA; best-effort cookie warm; returns body string)

```java
package in.arthayantra.marketdata.nse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Minimal anti-bot NSE fetcher: browser UA + best-effort homepage cookie warm. Reused by all
 *  NSE-EOD sources. NSE's data/api endpoints serve with a UA; the homepage 403s and is ignored. */
@Component
public class NseHttpClient {

  private static final String UA =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/120.0.0.0 Safari/537.36";

  private final RestClient http;
  private final String baseUrl;

  public NseHttpClient(
      RestClient.Builder builder, @Value("${artha.nse.base-url:https://www.nseindia.com}") String baseUrl) {
    this.baseUrl = baseUrl;
    this.http =
        builder
            .defaultHeader(HttpHeaders.USER_AGENT, UA)
            .defaultHeader(HttpHeaders.ACCEPT, "*/*")
            .defaultHeader(HttpHeaders.REFERER, baseUrl + "/")
            .build();
  }

  /** GET a path under the NSE base URL, returning the raw body (JSON/CSV). */
  public String get(String path) {
    return http.get().uri(baseUrl + path).retrieve().body(String.class);
  }
}
```

- [ ] **Step 2: Compile** (`package -DskipTests`). Commit.

---

## Task 3: `FiiDiiFetcher` port + Live parser (TDD)

**Files:** Create `.../nse/FiiDiiFetcher.java`, `.../nse/LiveFiiDiiFetcher.java`, test `.../nse/LiveFiiDiiFetcherTest.java`

- [ ] **Step 1: Port + row record**

```java
package in.arthayantra.marketdata.nse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface FiiDiiFetcher {
  record FiiDiiRow(LocalDate date, String category, BigDecimal buy, BigDecimal sell, BigDecimal net) {}
  List<FiiDiiRow> fetchLatest();
}
```

- [ ] **Step 2: Failing test** (parse the spike-captured JSON)

```java
package in.arthayantra.marketdata.nse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiveFiiDiiFetcherTest {

  private static final String JSON =
      "[{\"buyValue\":\"18877.03\",\"category\":\"DII\",\"date\":\"12-Jun-2026\","
          + "\"netValue\":\"5341.29\",\"sellValue\":\"13535.74\"},"
          + "{\"buyValue\":\"12064.61\",\"category\":\"FII/FPI\",\"date\":\"12-Jun-2026\","
          + "\"netValue\":\"-1082.18\",\"sellValue\":\"13146.79\"}]";

  @Test
  void parsesNseFiiDiiJson() {
    FiiDiiFetcher fetcher =
        new LiveFiiDiiFetcher(new StubClient(JSON), new ObjectMapper());

    List<FiiDiiFetcher.FiiDiiRow> rows = fetcher.fetchLatest();

    assertThat(rows).hasSize(2);
    FiiDiiFetcher.FiiDiiRow fii =
        rows.stream().filter(r -> r.category().equals("FII/FPI")).findFirst().orElseThrow();
    assertThat(fii.date()).isEqualTo(LocalDate.of(2026, 6, 12));
    assertThat(fii.net()).isEqualByComparingTo(new BigDecimal("-1082.18"));
    assertThat(fii.buy()).isEqualByComparingTo(new BigDecimal("12064.61"));
  }

  /** A NseHttpClient stub returning canned JSON (subclass; NseHttpClient is concrete). */
  static class StubClient extends NseHttpClient {
    private final String body;
    StubClient(String body) {
      super(org.springframework.web.client.RestClient.builder(), "https://x");
      this.body = body;
    }
    @Override
    public String get(String path) {
      return body;
    }
  }
}
```

- [ ] **Step 3: Run — expect RED** (`LiveFiiDiiFetcher` missing).

- [ ] **Step 4: Implement `LiveFiiDiiFetcher`**

```java
package in.arthayantra.marketdata.nse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Parses NSE /api/fiidiiTradeReact (B-1b). */
@Component
@Profile("live")
public class LiveFiiDiiFetcher implements FiiDiiFetcher {

  private static final DateTimeFormatter NSE_DATE =
      DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

  private final NseHttpClient client;
  private final ObjectMapper mapper;

  public LiveFiiDiiFetcher(NseHttpClient client, ObjectMapper mapper) {
    this.client = client;
    this.mapper = mapper;
  }

  @Override
  public List<FiiDiiRow> fetchLatest() {
    try {
      JsonNode arr = mapper.readTree(client.get("/api/fiidiiTradeReact"));
      List<FiiDiiRow> rows = new ArrayList<>();
      for (JsonNode n : arr) {
        rows.add(
            new FiiDiiRow(
                LocalDate.parse(n.path("date").asText(), NSE_DATE),
                n.path("category").asText(),
                new BigDecimal(n.path("buyValue").asText()),
                new BigDecimal(n.path("sellValue").asText()),
                new BigDecimal(n.path("netValue").asText())));
      }
      return rows;
    } catch (Exception e) {
      throw new IllegalStateException("NSE FII/DII fetch/parse failed: " + e.getMessage(), e);
    }
  }
}
```

- [ ] **Step 5: Run — GREEN.** Commit.

- [ ] **Step 6: `MockFiiDiiFetcher`** (`@Profile("!live")`, returns one stub row so non-live contexts have a bean)

```java
package in.arthayantra.marketdata.nse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!live")
public class MockFiiDiiFetcher implements FiiDiiFetcher {
  @Override
  public List<FiiDiiRow> fetchLatest() {
    return List.of(
        new FiiDiiRow(LocalDate.now(java.time.ZoneOffset.UTC), "FII/FPI",
            new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("10")));
  }
}
```

---

## Task 4: Repository (upsert)

**Files:** Create `.../nse/NseEodFiiDiiRepository.java`

```java
package in.arthayantra.marketdata.nse;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NseEodFiiDiiRepository {
  private final JdbcTemplate jdbc;
  public NseEodFiiDiiRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public void upsertAll(List<FiiDiiFetcher.FiiDiiRow> rows) {
    jdbc.batchUpdate(
        """
        INSERT INTO nse_eod_fii_dii (trade_date, category, buy_value, sell_value, net_value, fetched_at)
        VALUES (?, ?, ?, ?, ?, now())
        ON CONFLICT (trade_date, category) DO UPDATE SET
          buy_value = EXCLUDED.buy_value, sell_value = EXCLUDED.sell_value,
          net_value = EXCLUDED.net_value, fetched_at = now()
        """,
        rows, rows.size(),
        (ps, r) -> {
          ps.setObject(1, r.date());
          ps.setString(2, r.category());
          ps.setBigDecimal(3, r.buy());
          ps.setBigDecimal(4, r.sell());
          ps.setBigDecimal(5, r.net());
        });
  }
}
```

Commit.

---

## Task 5: Scheduler + read endpoint

**Files:** Create `.../nse/NseEodScheduler.java`, `.../nse/NseFiiDiiController.java`

- [ ] **Step 1: Scheduler** (daily ~19:00 IST; also one fetch on startup for immediate data)

```java
package in.arthayantra.marketdata.nse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily NSE EOD pull (B-1b). FII/DII first; later sources add their fetch here. */
@Component
public class NseEodScheduler {
  private static final Logger log = LoggerFactory.getLogger(NseEodScheduler.class);
  private final FiiDiiFetcher fiiDii;
  private final NseEodFiiDiiRepository repo;

  public NseEodScheduler(FiiDiiFetcher fiiDii, NseEodFiiDiiRepository repo) {
    this.fiiDii = fiiDii;
    this.repo = repo;
  }

  @Scheduled(cron = "${artha.nse.eod-cron:0 0 19 * * MON-FRI}", zone = "Asia/Kolkata")
  @EventListener(ApplicationReadyEvent.class)
  public void pullFiiDii() {
    try {
      var rows = fiiDii.fetchLatest();
      repo.upsertAll(rows);
      log.info("NSE FII/DII EOD upserted {} rows", rows.size());
    } catch (RuntimeException e) {
      log.warn("NSE FII/DII EOD pull failed (will retry next schedule): {}", e.getMessage());
    }
  }
}
```

- [ ] **Step 2: Read endpoint** `GET /api/v1/fii-dii/cash` → `{items:[...]}` envelope (latest date).

```java
package in.arthayantra.marketdata.nse;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fii-dii")
public class NseFiiDiiController {
  private final JdbcTemplate jdbc;
  public NseFiiDiiController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @GetMapping("/cash")
  public Map<String, Object> cash() {
    List<Map<String, Object>> items =
        jdbc.queryForList(
            "SELECT trade_date, category, buy_value, sell_value, net_value FROM nse_eod_fii_dii"
                + " WHERE trade_date = (SELECT max(trade_date) FROM nse_eod_fii_dii) ORDER BY category");
    return Map.of("items", items);
  }
}
```

Commit.

---

## Task 6: Config + deploy + live verify + ship

- [ ] **Step 1:** `application.yml` — add to the live `artha:` block:
```yaml
  nse:
    base-url: https://www.nseindia.com
    eod-cron: "0 0 19 * * MON-FRI"
```
- [ ] **Step 2:** Full market-data suite green (`-am test`) + ModularityTest (new `nse` package depends only on `marketcalendar`/jackson — no cycle).
- [ ] **Step 3:** Build JAR; `compose build market-data-service && up -d --no-deps market-data-service`; `up --no-deps flyway-init` (V012 → `artha`).
- [ ] **Step 4 (THE de-risk — container fetch):** on `ApplicationReadyEvent` the scheduler pulls once. Verify:
  `SELECT * FROM marketdata.nse_eod_fii_dii;` → 2 rows (DII + FII/FPI) for the latest NSE date. If the container 403s/blocks, the warn logs show it → fix the client (add explicit homepage cookie warm before the api call) and redeploy.
- [ ] **Step 5:** branch → PR → CI green → squash-merge.

---

## Self-review
- **Scope:** FII/DII only — proves the NSE-fetch-from-container pattern + the reusable `NseHttpClient`. Participant-OI is the next plan (reuses `NseHttpClient` + a CSV parser → `nse_eod_participant_oi`).
- **Container-fetch risk:** the host spike passed; the container does Kite HTTPS through the Avast CA already, so NSE HTTPS should work. Step 4 is the real proof; the warn-log path makes a block visible without breaking boot.
- **Modulith:** new `nse` package — no cross-module edge beyond `marketcalendar`/jackson; `@Profile` split mirrors `constituents`.
- **Contract:** new `/api/v1/fii-dii/cash` path → `ContractCaptureTest` re-capture may be needed (new path drifts the spec); re-capture with `-Dcontracts.capture=true` if ci-contracts flags it.
