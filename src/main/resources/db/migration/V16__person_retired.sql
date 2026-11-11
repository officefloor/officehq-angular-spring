-- The date a person is retired from; null while they are not retired. A person with a date counts as
-- retired at once, whatever the date. No claim dated after it can be added or saved for them.
ALTER TABLE person ADD COLUMN retired_from DATE;
