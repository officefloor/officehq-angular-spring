-- The month (1 to 12) the sheet's fiscal year starts in; a fiscal year is named FYyyyy by the calendar year its first
-- day falls in. Not dated.
ALTER TABLE sheet ADD COLUMN fiscal_start_month INT NOT NULL DEFAULT 1;
