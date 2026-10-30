-- A person's tags: trimmed, lower-cased, without repeats, sorted and comma separated; '' when they have none.
ALTER TABLE person ADD COLUMN tags VARCHAR(1000) NOT NULL DEFAULT '';
