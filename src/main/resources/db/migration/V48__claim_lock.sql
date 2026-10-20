-- Who has the claim's editor open, and the sheet's today when they opened it; the lock lapses once the
-- sheet's today changes.
ALTER TABLE claim ADD COLUMN locked_by VARCHAR(255);
ALTER TABLE claim ADD COLUMN locked_on DATE;
