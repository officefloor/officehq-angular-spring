-- A person's tags: trimmed, lower-cased, without repeats, sorted and comma separated; null for none.
ALTER TABLE person ADD COLUMN tags VARCHAR(600);
