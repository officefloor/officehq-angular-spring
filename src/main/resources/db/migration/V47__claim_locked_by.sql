-- The user whose open editor locks the claim for everyone else, until they save or cancel it or
-- the sheet's today changes; none while unlocked.
ALTER TABLE claim ADD COLUMN locked_by VARCHAR(100);
