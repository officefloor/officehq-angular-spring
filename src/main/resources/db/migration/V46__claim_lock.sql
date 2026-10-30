-- The user whose open editor locks a DRAFT claim for everyone else; null when no editor holds it.
ALTER TABLE claim ADD COLUMN locked_by VARCHAR(100);
