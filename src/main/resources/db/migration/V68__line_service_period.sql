-- The service period a line is billed for, both ends included; null for none. Its quantity times unit price is then the
-- price of a whole month, prorated by calendar month over the period.
ALTER TABLE invoice_line ADD COLUMN period_start DATE;
ALTER TABLE invoice_line ADD COLUMN period_end DATE;
