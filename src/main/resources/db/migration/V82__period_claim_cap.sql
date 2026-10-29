-- The most OPEN or PENDING claims a person may have in the period (those due by a date in it); null for no cap.
ALTER TABLE period ADD COLUMN claim_cap INTEGER;
