-- Every invoice carries the date it was issued (sent out) and the date payment is due.
-- Backfill any existing rows: issued today, due 30 days later.
ALTER TABLE invoice ADD COLUMN issued_date DATE DEFAULT CURRENT_DATE NOT NULL;
ALTER TABLE invoice ADD COLUMN due_date DATE;
UPDATE invoice SET due_date = DATEADD('DAY', 30, issued_date) WHERE due_date IS NULL;
ALTER TABLE invoice ALTER COLUMN due_date SET NOT NULL;

ALTER TABLE invoice ADD CONSTRAINT invoice_due_after_issued CHECK (due_date >= issued_date);
