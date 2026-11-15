-- Whether a steward set the claim's due-by directly: a holiday added later then leaves it.
ALTER TABLE claim ADD COLUMN due_by_set BOOLEAN NOT NULL DEFAULT FALSE;
