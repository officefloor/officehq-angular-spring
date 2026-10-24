-- The most OPEN or PENDING claims each person may have in the period; null for no cap.
ALTER TABLE period ADD COLUMN claim_cap INT;
