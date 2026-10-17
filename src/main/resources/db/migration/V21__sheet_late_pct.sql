-- The sheet's late percent: an overdue claim's live due gains it, of its floored due, for every full 30 days overdue.
ALTER TABLE sheet ADD COLUMN late_pct DECIMAL(5, 2) DEFAULT 0.00 NOT NULL;
