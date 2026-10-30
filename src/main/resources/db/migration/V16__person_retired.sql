-- The date a person is retired from; null while they are not retired. A person with a date set is
-- retired at once, whatever the date; no claim dated after it can be added for them.
ALTER TABLE person ADD COLUMN retired_from DATE;
