-- A steward's flag on a person: its reason, shown while the sheet's today is on or before its until-date, when only a
-- steward may open a claim of the person. A later flag replaces it; none: both null.
ALTER TABLE person ADD COLUMN flag_reason VARCHAR(200);
ALTER TABLE person ADD COLUMN flag_until DATE;
