-- A period may cap how many OPEN or PENDING claims each person has in it (by due-by); null for no cap.
ALTER TABLE period ADD COLUMN claim_cap INTEGER;
