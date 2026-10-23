-- Whether a steward set the claim's due-by directly: a due-by set this way stays as set, a holiday
-- added later does not work it out again.
ALTER TABLE claim ADD COLUMN due_by_set BOOLEAN NOT NULL DEFAULT FALSE;
