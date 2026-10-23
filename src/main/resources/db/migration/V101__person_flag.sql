-- A steward can flag a person with a reason until a date: while the sheet's today is on or before
-- it, only a steward may open a claim of theirs. Both are null while the person was never flagged.
ALTER TABLE person ADD COLUMN flag_reason VARCHAR(200);
ALTER TABLE person ADD COLUMN flag_until DATE;
