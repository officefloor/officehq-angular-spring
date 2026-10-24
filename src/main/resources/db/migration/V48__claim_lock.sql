-- A claim's editor, once opened, locks the claim for other users: locked_by is the user holding it and
-- locked_on the sheet's today when it was taken. Null while no one holds it; saving or cancelling the
-- editor, or changing the sheet's today, releases it.
ALTER TABLE claim ADD COLUMN locked_by VARCHAR(100);
ALTER TABLE claim ADD COLUMN locked_on DATE;
