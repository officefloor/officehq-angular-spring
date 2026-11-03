-- The sheet's fiscal year start month (1 to 12): a fiscal year runs from the first day of it. Not dated.
ALTER TABLE sheet ADD COLUMN fiscal_start_month INT NOT NULL DEFAULT 1;
