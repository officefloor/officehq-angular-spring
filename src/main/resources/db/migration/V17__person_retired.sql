-- A person can be retired from a date; null while they are not. A person with a date set counts as
-- retired at once, whatever the date. No claim dated after it can be added to them, or edited to it.
ALTER TABLE person ADD COLUMN retired_from DATE;
