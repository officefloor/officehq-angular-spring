-- A steward's flag on a person: its reason and the date it is in force until, inclusive; null when never flagged.
ALTER TABLE person ADD COLUMN flag_reason VARCHAR(200);
ALTER TABLE person ADD COLUMN flag_until DATE;
