-- A claim records who opened it; none while it is a draft. The user who opened a claim cannot settle it.
ALTER TABLE claim ADD COLUMN opened_by VARCHAR(255);
