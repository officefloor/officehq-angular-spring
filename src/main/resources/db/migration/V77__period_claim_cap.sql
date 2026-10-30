-- The most OPEN or PENDING claims a person may have in the period; null for no cap.
ALTER TABLE period ADD COLUMN claim_cap INT;
