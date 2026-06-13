-- Phase 22 (S8 part 1 / C-2.15): APPEND-ONLY point-in-time index membership.
-- NSE publishes only the CURRENT list, so history is reconstructable ONLY
-- from capture start - the pre-accrual portion of any backtest window
-- permanently carries the survivorship-bias caveat. Rows are never UPDATEd
-- or DELETEd; each (index, as_of_date) is one immutable snapshot. No
-- cross-schema FK anywhere (D10) - consumers resolve via REST.

CREATE TABLE index_constituents (
  index_name    TEXT NOT NULL,
  as_of_date    DATE NOT NULL,
  exchange      TEXT NOT NULL,
  tradingsymbol TEXT NOT NULL,
  fetched_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (index_name, as_of_date, exchange, tradingsymbol)
);

-- the read path: latest snapshot per index, exact date with ?asOf=
CREATE INDEX idx_index_constituents_lookup ON index_constituents (index_name, as_of_date DESC);
