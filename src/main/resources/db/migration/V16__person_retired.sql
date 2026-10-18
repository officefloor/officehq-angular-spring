-- A person can be retired from a date: a claim of theirs dated after it is refused. Null: not retired.
ALTER TABLE person ADD COLUMN retired_from DATE;
