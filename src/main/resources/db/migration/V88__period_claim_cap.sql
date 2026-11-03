-- The most open (or pending) claims a person may have in a period; null for no cap.
ALTER TABLE period ADD COLUMN claim_cap INT;
ALTER TABLE period_revision ADD COLUMN claim_cap INT;
