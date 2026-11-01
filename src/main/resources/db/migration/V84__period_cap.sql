-- A period may cap the number of OPEN or PENDING claims each person may have in it; null for no cap.
ALTER TABLE period ADD COLUMN claim_cap INTEGER;
