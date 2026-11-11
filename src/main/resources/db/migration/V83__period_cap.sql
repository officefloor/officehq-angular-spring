-- A period's cap on the OPEN or PENDING claims each person may have in it; null means none.
ALTER TABLE period ADD COLUMN claim_cap INTEGER CHECK (claim_cap >= 1);
