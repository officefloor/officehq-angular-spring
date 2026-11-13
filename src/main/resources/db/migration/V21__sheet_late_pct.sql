-- A sheet's late percent (two decimals): an overdue claim's due gains it of its figure for every
-- full 30 days it is overdue. A sheet starts at 0.00.
ALTER TABLE sheet ADD COLUMN late_pct DECIMAL(12, 2) DEFAULT 0.00 NOT NULL;
