-- The month the sheet's fiscal year starts in, 1 to 12; not dated.
ALTER TABLE sheet ADD COLUMN fiscal_start_month INT NOT NULL DEFAULT 1;
