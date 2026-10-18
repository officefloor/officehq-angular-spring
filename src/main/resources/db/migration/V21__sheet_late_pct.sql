-- The sheet's late percent: an overdue claim gains this percent of its figure for every full 30 days
-- it is overdue (two decimals). It starts at 0.00.
ALTER TABLE sheet ADD COLUMN late_pct DECIMAL(5, 2) DEFAULT 0.00 NOT NULL;
