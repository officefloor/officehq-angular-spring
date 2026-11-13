-- The date a person is retired from; null while they are not retired. A person with a date set
-- counts as retired whatever the date.
ALTER TABLE person ADD COLUMN retired_from DATE;
