-- The month (1 to 12) the sheet's fiscal year starts in; a fiscal year is named FY<yyyy> by the
-- calendar year its first day falls in.
ALTER TABLE sheet ADD COLUMN fiscal_start_month INTEGER NOT NULL DEFAULT 1;
