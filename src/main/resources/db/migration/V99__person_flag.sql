-- A steward's flag on a person: a reason, in force while the sheet's today is on or before its until-date. While
-- in force only a steward may open a claim of that person. Null for anyone never flagged.
ALTER TABLE person ADD COLUMN flag_reason VARCHAR(200);
ALTER TABLE person ADD COLUMN flag_until DATE;
