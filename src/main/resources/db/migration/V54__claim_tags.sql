-- The claim's tags: trimmed, lower-cased, without repeats, sorted and joined by commas; none when not given.
ALTER TABLE claim ADD COLUMN tags VARCHAR(1000);
