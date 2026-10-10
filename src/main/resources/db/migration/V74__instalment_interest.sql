-- An instalment paid late accrues interest: a flat amount for each day it is past its due date while
-- still unpaid. The rate is set per invoice; existing invoices charge none.
ALTER TABLE invoice ADD COLUMN instalment_interest_per_day DECIMAL(12, 2) DEFAULT 0 NOT NULL;

ALTER TABLE invoice ADD CONSTRAINT invoice_instalment_interest_per_day_non_negative CHECK (instalment_interest_per_day >= 0);
