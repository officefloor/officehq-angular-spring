-- The percent of its figure an overdue claim gains for every full 30 days it is overdue; a sheet
-- starts at 0.00.
ALTER TABLE sheet ADD COLUMN late_pct DECIMAL(7, 2) DEFAULT 0.00 NOT NULL;
