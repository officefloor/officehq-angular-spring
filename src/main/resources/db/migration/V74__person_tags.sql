-- The person's tags: trimmed, lower-cased, without repeats, sorted and joined by commas; none when not given.
ALTER TABLE person ADD COLUMN tags VARCHAR(1000);
