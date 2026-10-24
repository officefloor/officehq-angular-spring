-- A person's tags: trimmed, lower-cased, without repeats, sorted and comma separated; null when they
-- have none. A claim counts in a tag's total when it or its person carries the tag.
ALTER TABLE person ADD COLUMN tags VARCHAR(1000);
