-- Part of a sent or part-paid invoice can be written off as bad debt. The written-off part is no longer
-- owed; the rest still is. Existing invoices have nothing written off.
ALTER TABLE invoice ADD COLUMN write_off_amount DECIMAL(12, 2) NOT NULL DEFAULT 0;
ALTER TABLE invoice ADD CONSTRAINT invoice_write_off_amount_not_negative CHECK (write_off_amount >= 0);
