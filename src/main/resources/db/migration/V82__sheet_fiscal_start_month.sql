-- A sheet's fiscal year start month, 1 to 12: a fiscal year runs twelve months from the first of it.
ALTER TABLE sheet ADD COLUMN fiscal_start_month INTEGER NOT NULL DEFAULT 1;
