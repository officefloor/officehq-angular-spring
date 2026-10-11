-- How a payment was made: BANK (a bank transfer) or CASH, counted out in coins no smaller than 0.05.
ALTER TABLE payment ADD COLUMN method VARCHAR(8) DEFAULT 'BANK' NOT NULL;
-- The cash rounding a cash payment settled the invoice with: what it paid in cash less what was due
-- (signed); 0 when none.
ALTER TABLE invoice ADD COLUMN cash_rounding DECIMAL(19, 2) DEFAULT 0 NOT NULL;
