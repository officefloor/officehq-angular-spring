-- A person's tags: trimmed, lower-cased, without repeats, sorted and comma separated; none when not given.
-- Each also counts among the tags used on the sheet (sheet_tag).
ALTER TABLE person ADD COLUMN tags VARCHAR(2000);
