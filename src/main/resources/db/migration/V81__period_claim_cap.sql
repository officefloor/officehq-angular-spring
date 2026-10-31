-- A period's cap on the number of OPEN or PENDING claims a person may have in it (claims whose due-by falls inside
-- it); none means no cap.
ALTER TABLE period ADD COLUMN claim_cap INTEGER;
