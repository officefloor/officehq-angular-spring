-- The signed-in user who opened a claim; null for a claim never opened. That user cannot settle it.
ALTER TABLE claim ADD COLUMN opened_by VARCHAR(100);
