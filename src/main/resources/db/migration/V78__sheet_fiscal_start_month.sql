-- The month (1 to 12) the sheet's fiscal years start in; 1 (January) unless set.
ALTER TABLE sheet ADD COLUMN fiscal_start_month INTEGER NOT NULL DEFAULT 1 CHECK (fiscal_start_month BETWEEN 1 AND 12);
