-- Who opened a claim: the signed-in user who moved it from DRAFT to OPEN; null for a claim never
-- opened. That user may not settle it.
ALTER TABLE claim ADD COLUMN opened_by VARCHAR(200);
