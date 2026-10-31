-- The date a person is retired from: null while they are not retired. A person with one set counts
-- as retired at once, whatever the date; a claim of theirs dated after it is refused.
ALTER TABLE person ADD COLUMN retired_from DATE;
