-- A steward's flag on a person: a reason, in force while the sheet's today is on or before its until-date.
ALTER TABLE person ADD COLUMN flag_reason VARCHAR(1000);
ALTER TABLE person ADD COLUMN flag_until DATE;
