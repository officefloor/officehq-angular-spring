-- The sheet's late percent: an overdue claim's due gains this percent of its figure for every
-- full 30 days it is overdue. Never bound by a statement; frozen in when the claim is settled.
ALTER TABLE sheet ADD COLUMN late_pct DECIMAL(5, 2) DEFAULT 0.00 NOT NULL;
