-- A claim's tags: trimmed, lower case, no repeats, sorted and comma separated (null: none); and every
-- tag it has ever carried, in the same form (null: none), so the sheet lists each tag ever used on it.
ALTER TABLE claim ADD COLUMN tags VARCHAR(2000);
ALTER TABLE claim ADD COLUMN tags_used VARCHAR(4000);
