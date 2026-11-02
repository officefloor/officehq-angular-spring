-- The day a person is retired from; null while they are not retired. A person with a day set counts
-- as retired at once, whatever the day. No claim of theirs may be dated after it.
ALTER TABLE person ADD COLUMN retired_from DATE;
