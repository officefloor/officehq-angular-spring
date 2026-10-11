-- A credit note may be for more than its original still owes. What the original took of it is kept in
-- credit_applied (credit_retention of that cleared retention it held back); the rest is held on the
-- client's account as a payment naming the credit note (credit_note), which cannot be reversed.
ALTER TABLE invoice ADD COLUMN credit_applied DECIMAL(19, 2);
ALTER TABLE invoice ADD COLUMN credit_retention DECIMAL(19, 2) DEFAULT 0 NOT NULL;
UPDATE invoice SET credit_applied = credit_total WHERE credit_total IS NOT NULL;
ALTER TABLE payment ADD COLUMN credit_note BIGINT;
