-- The sheet's late percent: an overdue claim's due gains it of its figure for every full 30 days
-- it is overdue. Every sheet starts at 0.00.
ALTER TABLE sheet ADD COLUMN late_pct DECIMAL(7, 2) DEFAULT 0.00 NOT NULL;
