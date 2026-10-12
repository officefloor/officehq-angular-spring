-- How many approvals an invoice has at its approved total: a draft whose grand total is more than twice
-- the approval limit needs two. An invoice approved before the count was kept has one approval.
ALTER TABLE invoice ADD COLUMN approvals INT NOT NULL DEFAULT 0;
UPDATE invoice SET approvals = 1 WHERE approved_total IS NOT NULL;
