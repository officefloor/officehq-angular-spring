-- The percent of its figure an overdue claim's due gains for every full 30 days overdue; it starts at 0.00.
ALTER TABLE sheet ADD COLUMN late_pct DECIMAL(14, 2) DEFAULT 0.00 NOT NULL;
