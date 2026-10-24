-- Who opened a claim; null while it has not been opened. The user who opened a claim cannot settle it.
ALTER TABLE claim ADD COLUMN opened_by VARCHAR(100);
