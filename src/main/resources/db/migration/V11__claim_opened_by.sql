-- Who opened a claim: the signed-in user who moved it from DRAFT to OPEN. Null for a claim never
-- opened. That user may not also settle it.
ALTER TABLE claim ADD COLUMN opened_by VARCHAR(100);
