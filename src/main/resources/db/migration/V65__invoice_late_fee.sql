-- A sent invoice that is overdue accrues a late fee: a flat amount for each day past its due date.
-- Existing invoices charge none.
ALTER TABLE invoice ADD COLUMN late_fee_per_day DECIMAL(12, 2) DEFAULT 0 NOT NULL;

ALTER TABLE invoice ADD CONSTRAINT invoice_late_fee_per_day_non_negative CHECK (late_fee_per_day >= 0);
