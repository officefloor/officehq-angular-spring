-- The month a fiscal year starts in (1 to 12); a fiscal year is named by the calendar year its first day
-- falls in. 1 unless set. Not dated.
ALTER TABLE sheet ADD COLUMN fiscal_start_month INTEGER NOT NULL DEFAULT 1 CHECK (fiscal_start_month BETWEEN 1 AND 12);
