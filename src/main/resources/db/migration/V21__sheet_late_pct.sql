-- The sheet's late percent: an overdue claim's due gains this percent of its figure for every full
-- 30 days it is overdue. 0.00 on a new sheet.
ALTER TABLE sheet ADD COLUMN late_pct DECIMAL(5, 2) DEFAULT 0.00 NOT NULL CHECK (late_pct >= 0);
