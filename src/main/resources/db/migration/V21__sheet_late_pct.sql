-- The sheet's late percent: an overdue claim's due gains this percent of its figure for every full 30 days it is overdue.
ALTER TABLE sheet ADD COLUMN late_pct NUMERIC(5, 2) DEFAULT 0.00 NOT NULL CHECK (late_pct >= 0);
