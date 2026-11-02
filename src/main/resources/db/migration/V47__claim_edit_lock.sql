-- Who holds the claim's editor open (null when no one): the claim is locked for every other user
-- until it is saved or cancelled, or the sheet's today changes.
ALTER TABLE claim ADD COLUMN edit_locked_by VARCHAR(100);
