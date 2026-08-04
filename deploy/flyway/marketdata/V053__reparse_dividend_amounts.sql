-- Re-derives `dividends.amount` for the two subject shapes the old parser got wrong: compound NSE
-- subjects it under-reported, and the BSE dash shape it never matched at all.
--
-- ============================================================================================
-- Part 1 -- compound NSE subjects, under-reported (amount IS NOT NULL, but too small).
--
-- DividendSubjectParser used to take only the FIRST `Rs|Re` amount in a subject, so a compound
-- payout such as INDOBORAX 2026-07-21 "Dividend - Rs 10 Per Share/Special Dividend - Rs 30 Per
-- Share" stored 10.00 instead of 40.00. The parser now adds the amounts; this repairs the rows
-- already written. The feed job re-fetches only a 420-day window
-- (`artha.bhavcopy.ca-lookback-days`) and its upsert does overwrite `amount`, so in-window rows
-- would self-heal on the next successful run -- but 2 of the 34 are ALREADY outside that window
-- and would never be revisited, and the window slides one day per day. V048 kept the raw `subject`
-- precisely so amounts could be re-parsed without re-fetching.
--
-- ⚠️ Deliberately NOT summed: the InvIT/REIT "Distribution - Rs T Per Unit Consisting Of <legs>"
-- shape, where the leading amount is the stated TOTAL and the rest itemise it. Adding those would
-- double-count (exactly 2x). Measured on live `artha` 2026-08-03: 102 rows state more than one
-- amount -- 68 itemised (excluded here), 34 genuinely compound (repaired here). The `consist`
-- /`compris` marker is matched as a loose substring, mirroring the Java parser, because the feed
-- emits run-together text such as "Consistingrs 1.1113".
--
-- Nothing reads this column for price adjustment -- dividends are cash-flow records only (V048) --
-- so this is a latent-data repair, not a price change. Idempotent: re-running updates no rows.
UPDATE dividends d
SET amount = r.recomputed
FROM (
    SELECT
        exchange,
        tradingsymbol,
        ex_date,
        (
            SELECT sum((g[1])::numeric)
            FROM regexp_matches(
                     subject, '\y(?:rs|re)\.?\s*([0-9]+(?:\.[0-9]+)?)', 'gi'
                 ) AS g
        ) AS recomputed
    FROM dividends
    WHERE amount IS NOT NULL
      AND subject ~* '\ydividend\y'
      AND subject !~* '(consist|compris)'
) AS r
WHERE d.exchange = r.exchange
  AND d.tradingsymbol = r.tradingsymbol
  AND d.ex_date = r.ex_date
  AND r.recomputed IS NOT NULL
  AND d.amount IS DISTINCT FROM r.recomputed;

-- ============================================================================================
-- Part 2 -- the BSE dash shape, never parsed at all (amount IS NULL).
--
-- BSE renders the cash amount as a separate feed field, so its purpose text reads
-- "Final Dividend - Rs. - 10.0000": a dash sits between the rupee marker and the number, which the
-- `\y(?:rs|re)\.?\s*<num>` pattern above cannot cross. Measured on live `artha` 2026-08-03: 2,574
-- of 2,574 BSE rows -- every BSE row ever captured -- stored a NULL amount for this reason, against
-- 0 of 2,169 NSE rows. All 2,574 use the identical `Rs. - ` separator, carry exactly ONE numeric
-- token, and one of four prefixes (Final/Interim/Special/plain Dividend).
--
-- Mirrors the Java fallback exactly, including both of its safety properties:
--   * FALLBACK ONLY -- the `!~*` guard skips any subject where the primary `Rs <num>` form matches,
--     so no row that already parses can change value. Part 1's population and this one are disjoint
--     (`amount IS NOT NULL` vs `IS NULL`), so the two statements cannot fight over a row.
--   * EXACTLY ONE amount -- a second dash amount is a shape we have never seen and cannot classify
--     as additive or itemised, so it is left NULL rather than guessed. A NULL is a visibly absent
--     number; a wrong number is indistinguishable from a right one.
--
-- Non-dividend actions cannot be reached: `isDividend`/`\ydividend\y` gates both, and a subject
-- carrying a split/bonus ratio is consumed by CorporateActionSubjectParser before the dividend
-- branch runs -- confirmed live, 0 rows in `eod_corporate_actions` contain the word "dividend".
--
-- Why a backfill rather than waiting for the feed: the CA sync re-fetches only a 420-day window
-- (`artha.bhavcopy.ca-lookback-days`) and its upsert does overwrite `amount`, so in-window rows
-- would self-heal on the next successful run. 67 of the 2,574 are ALREADY outside that window and
-- would never be revisited, and the window slides one day per day. V048 kept the raw `subject`
-- precisely so amounts could be re-derived without re-fetching. Idempotent: re-running updates 0.
UPDATE dividends d
SET amount = (regexp_match(d.subject, '\y(?:rs|re)\.?\s*-\s*([0-9]+(?:\.[0-9]+)?)', 'i'))[1]::numeric
WHERE d.amount IS NULL
  AND d.subject ~* '\ydividend\y'
  AND d.subject !~* '\y(?:rs|re)\.?\s*[0-9]'
  AND (
        SELECT count(*)
        FROM regexp_matches(d.subject, '\y(?:rs|re)\.?\s*-\s*[0-9]+(?:\.[0-9]+)?', 'gi')
      ) = 1;
