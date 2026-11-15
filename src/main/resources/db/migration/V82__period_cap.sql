-- A period may cap the number of OPEN or PENDING claims a person has in it (none when null).
ALTER TABLE period ADD COLUMN claim_cap INTEGER;
