-- A steward's flag on a person: a reason and the date it is in force until, inclusive; null while
-- the person was never flagged.
ALTER TABLE person ADD COLUMN flag_reason VARCHAR(200);
ALTER TABLE person ADD COLUMN flag_until DATE;
