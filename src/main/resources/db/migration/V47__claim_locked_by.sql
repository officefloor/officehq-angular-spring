-- The user whose editor is open on a claim, locking it for every other user until they save or cancel
-- it, or the sheet's today changes; none while unlocked.
ALTER TABLE claim ADD COLUMN locked_by VARCHAR(200);
