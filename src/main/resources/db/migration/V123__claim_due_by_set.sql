-- Whether a steward set a claim's due-by directly: such a due-by stays as set, and adding a holiday does not move it.
ALTER TABLE claim ADD COLUMN due_by_set BOOLEAN NOT NULL DEFAULT FALSE;
