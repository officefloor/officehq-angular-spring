-- A steward may flag a person with a reason until a day (included): while the sheet's today is on
-- or before it, the reason shows on the person's row and only a steward may open a claim of theirs.
-- A person has at most one flag; flagging again replaces it.
ALTER TABLE person ADD COLUMN flag_reason VARCHAR(200);
ALTER TABLE person ADD COLUMN flag_until DATE;
