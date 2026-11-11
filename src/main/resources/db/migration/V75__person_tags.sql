-- A person's tags: trimmed, lower-cased, without repeats, sorted and joined by commas. Null: the person has none.
ALTER TABLE person ADD COLUMN tags VARCHAR(1000);
