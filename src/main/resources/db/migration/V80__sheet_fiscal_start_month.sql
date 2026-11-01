-- The sheet's fiscal year start month (not dated), 1 to 12: a fiscal year runs from its first day.
ALTER TABLE sheet ADD COLUMN fiscal_start_month INTEGER NOT NULL DEFAULT 1;
