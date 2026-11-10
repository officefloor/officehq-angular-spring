-- The month (1 to 12) the sheet's fiscal year starts in; not dated.
ALTER TABLE sheet ADD COLUMN fiscal_start_month INT DEFAULT 1 NOT NULL CHECK (fiscal_start_month BETWEEN 1 AND 12);
