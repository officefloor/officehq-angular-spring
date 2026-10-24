-- The date a person is retired from; null while they are not retired. A person with the date set
-- is retired at once, whatever the date; claims dated after it are refused.
ALTER TABLE person ADD COLUMN retired_from DATE;
