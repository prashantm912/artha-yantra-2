# Phase 4 · Wave 3 — Market Holidays (manual test)

The oipulse **Market Holidays** (`docs/oipulse-study/features/market-holidays.md`), React route
`/features/market-holidays`, mega-menu **Features → Market Holidays**. A sortable, paginated reference table
of NSE trading holidays.

## What was built
- **BE (small add)** `MarketCalendar` now retains the published holiday **names** (the bundled
  `nse-trading-holidays.csv` is `ISO-date,name`) via a new `Holiday(date,name)` record + `holidayList()`
  accessor — the trading-day logic (date set) is unchanged. New endpoint
  `GET /api/v1/market/holidays` (Map-envelope `{items:[{date,day,description}]}`, day-of-week derived
  server-side, date-ascending).
- **FE** `useHolidays` hook + `MarketHolidaysPage` rendering the generic **DataTable** (sort + 20/page
  pagination + mobile cards): **Date · Day · Description · Validity**. The **Validity** badge
  (red `Passed` if the date < today IST, green `Coming` otherwise) is derived client-side.

## Preconditions
- Stack up; sign in. No market data needed — the list is the bundled calendar resource.

## Steps
1. Open **Features → Market Holidays**.
2. Verify the table: **Date · Day · Description · Validity**, sorted by Date ascending, 20 rows/page.
3. Each header sorts (▲/▼); the **Validity** column shows a red **Passed** badge for past dates and a
   green **Coming** badge for today-or-later.
4. A known row: **2026-01-26 · Monday · Republic Day**.
5. Narrow to phone width (~480px) → the table collapses to one card per holiday (label: value).

## Faithful divergences (documented)
- **All calendar-covered years** are listed (the bundled resource currently spans 2024–2026), not a single
  per-year view with a year selector — the `MarketCalendar` resource is the source of truth, and the
  Validity badge already distinguishes past from upcoming.
- **No `#` row-number column** — omitted because a static row number is misleading once the user re-sorts
  (oipulse's `#` is just initial display order).

## Verify (green at build)
`Push-Location frontend-react; npm run lint; npm run test:ci; npm run build`
BE: `mvnw -pl services/market-data-service -am test -Dtest='MarketCalendarTest,VixControllerIntegrationTest'`
(MarketCalendar name-retention + the `/holidays` endpoint IT).
Contract: recaptured (new `/holidays` path) + TS regen — additive, ci-contracts WARN.
