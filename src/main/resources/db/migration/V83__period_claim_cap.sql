-- A period may cap the number of open claims per person: a claim due by in it cannot be opened
-- while its person already has that many OPEN or PENDING claims due by in it. Null: no cap.
ALTER TABLE period ADD COLUMN claim_cap INT;
