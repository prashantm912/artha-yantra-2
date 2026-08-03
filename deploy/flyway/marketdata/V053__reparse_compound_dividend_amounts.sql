-- Re-derives `dividends.amount` for compound dividend subjects that the old parser under-reported.
--
-- DividendSubjectParser used to take only the FIRST `Rs|Re` amount in a subject, so a compound
-- payout such as INDOBORAX 2026-07-21 "Dividend - Rs 10 Per Share/Special Dividend - Rs 30 Per
-- Share" stored 10.00 instead of 40.00. The parser now adds the amounts; this repairs the rows
-- already written, which the feed job would otherwise never revisit -- it re-fetches only a
-- 420-day window (`artha.bhavcopy.ca-lookback-days`), so every affected row ages permanently out
-- of reach. V048 kept the raw `subject` precisely so amounts could be re-parsed without re-fetching.
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
