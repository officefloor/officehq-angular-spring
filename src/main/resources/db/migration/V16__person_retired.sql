-- A person can be retired from a date: no claim dated after it can be added for them. Null while
-- they are not retired. A retired person's claims keep counting in every total.
ALTER TABLE person ADD COLUMN retired_from DATE;
