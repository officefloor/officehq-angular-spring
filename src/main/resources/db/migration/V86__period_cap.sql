-- The most OPEN and PENDING claims a person may have in the period (a whole number of at least 1),
-- or null for no cap.
ALTER TABLE period ADD COLUMN claim_cap INT;
