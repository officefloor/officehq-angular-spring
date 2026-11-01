-- Who opened a claim: the signed-in user's name, given when it is opened. Empty while DRAFT.
-- That user cannot settle it.
ALTER TABLE claim ADD COLUMN opened_by VARCHAR(100);
