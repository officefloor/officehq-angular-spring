-- A SNAPSHOT is written as the sheet's today moves to another day: made_on is that day, the one the
-- snapshot first stood on (null for one written before it was kept: taken as the day after its own).
ALTER TABLE person_snapshot ADD COLUMN made_on DATE;
