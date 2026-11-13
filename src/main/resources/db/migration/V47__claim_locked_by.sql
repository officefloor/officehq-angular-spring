-- Opening a claim's editor locks the claim for other users: the user holding the lock, null when
-- no one does. Saving or cancelling the editor, or changing the sheet's today, releases it.
ALTER TABLE claim ADD COLUMN locked_by VARCHAR(255);
