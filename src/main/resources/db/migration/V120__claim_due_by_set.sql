-- Whether a steward set the claim's due-by directly: a due-by set this way stays as set, so a holiday added later
-- does not fix it again.
ALTER TABLE claim ADD COLUMN due_by_set BOOLEAN DEFAULT FALSE NOT NULL;
