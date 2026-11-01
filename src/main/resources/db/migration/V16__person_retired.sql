-- A person can be retired from a date; set, they count as retired at once whatever the date. No
-- claim of theirs dated after it can be added or edited to such a date. Empty while not retired.
ALTER TABLE person ADD COLUMN retired_from DATE;
