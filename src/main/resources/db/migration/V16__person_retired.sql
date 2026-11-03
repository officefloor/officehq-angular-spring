-- A person can be retired from a date. With a date set the person counts as retired at once,
-- whatever the date; no claim of theirs can be dated after it. None while not retired.
ALTER TABLE person ADD COLUMN retired_from DATE;
