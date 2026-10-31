-- Who has the claim's editor open, locking it for other users until they save or cancel or the sheet's today
-- changes; null when no one has.
ALTER TABLE claim ADD COLUMN locked_by VARCHAR(100);
