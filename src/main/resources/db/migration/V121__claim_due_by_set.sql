-- Whether a steward set the claim's due-by directly: it then stays as set, and a holiday added later
-- does not work it out again.
ALTER TABLE claim ADD COLUMN due_by_set BOOLEAN DEFAULT FALSE NOT NULL;
