-- A person's tags: lower-case, sorted and comma separated, without repeats. NULL when they have none.
ALTER TABLE person ADD COLUMN tags VARCHAR(1000);
