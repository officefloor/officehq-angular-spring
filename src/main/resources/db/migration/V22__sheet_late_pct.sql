-- A sheet's late percent: what an overdue claim gains of its figure for every full 30 days overdue; it starts at 0.00.
ALTER TABLE sheet ADD COLUMN late_pct DECIMAL(7, 2) DEFAULT 0.00 NOT NULL CHECK (late_pct >= 0);
