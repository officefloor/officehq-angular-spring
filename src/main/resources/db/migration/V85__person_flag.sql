-- A steward's flag on a person: its reason, in force while the sheet's today is on or before its
-- until-date. Null when the person was never flagged.
ALTER TABLE person ADD COLUMN flag_reason VARCHAR(200);
ALTER TABLE person ADD COLUMN flag_until DATE;
