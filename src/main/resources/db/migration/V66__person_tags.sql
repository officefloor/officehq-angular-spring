-- A person may carry tags: lower-cased, sorted and comma separated; null when they have none.
ALTER TABLE person ADD COLUMN tags VARCHAR(1000);
