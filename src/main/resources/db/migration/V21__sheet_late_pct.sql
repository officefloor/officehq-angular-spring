-- The percent of an overdue claim's figure added for every full 30 days it is overdue; it starts at 0.00.
ALTER TABLE sheet ADD COLUMN late_pct DECIMAL(7, 2) DEFAULT 0.00 NOT NULL CHECK (late_pct >= 0);
