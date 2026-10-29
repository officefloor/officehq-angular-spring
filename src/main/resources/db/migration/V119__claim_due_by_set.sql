-- Whether a steward set a claim's due-by directly: a due-by set so stays as set when a holiday is added later.
ALTER TABLE claim ADD COLUMN due_by_set BOOLEAN NOT NULL DEFAULT FALSE;
