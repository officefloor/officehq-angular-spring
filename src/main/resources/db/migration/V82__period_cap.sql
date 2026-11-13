-- A period's cap on the open claims a person may have in it: opening one more of a person's claims that
-- belong to the period is refused once they have this many OPEN or PENDING claims in it. None means no cap.
ALTER TABLE period ADD COLUMN claim_cap INTEGER CHECK (claim_cap >= 1);
