-- A period's cap on the OPEN or PENDING claims each person may have in it (none: no cap).
ALTER TABLE period ADD COLUMN claim_cap INTEGER CHECK (claim_cap > 0);
