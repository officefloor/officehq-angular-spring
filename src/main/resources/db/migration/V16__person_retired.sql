-- A person can be retired from a date: they count as retired at once, whatever the date, and no
-- claim dated after it can be added for them (or moved there). Null while not retired.
ALTER TABLE person ADD COLUMN retired_from DATE;
