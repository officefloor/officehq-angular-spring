-- A person's tags: trimmed, lower case, no repeats, sorted and comma separated (null: none); and every
-- tag they have ever carried, in the same form (null: none), so the sheet lists each tag ever used on it.
ALTER TABLE person ADD COLUMN tags VARCHAR(2000);
ALTER TABLE person ADD COLUMN tags_used VARCHAR(4000);
