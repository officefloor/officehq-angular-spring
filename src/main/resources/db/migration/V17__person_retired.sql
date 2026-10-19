-- A person can be retired from a date; claims dated after it are refused. Null while not retired.
ALTER TABLE person ADD COLUMN retired_from DATE;
