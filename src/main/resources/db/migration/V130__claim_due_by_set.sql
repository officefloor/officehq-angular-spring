-- Whether a steward set the claim's due-by directly: a due-by set so stays as set (a holiday added
-- later does not fix it again).
ALTER TABLE claim ADD COLUMN due_by_set BOOLEAN NOT NULL DEFAULT FALSE;
