-- Who opened a claim: the signed-in user who moved it from DRAFT to OPEN. None while a draft. That
-- user cannot settle it (four eyes).
ALTER TABLE claim ADD COLUMN opened_by VARCHAR(200);
