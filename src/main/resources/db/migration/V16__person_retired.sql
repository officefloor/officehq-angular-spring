-- The date a person is retired from; null while they are not retired. A person with a date set counts as retired
-- at once, and no claim dated after it can be added for them.
ALTER TABLE person ADD COLUMN retired_from DATE;
