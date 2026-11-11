-- Who opened a claim: the signed-in user's name, set when it is opened; null for a DRAFT. That user may not
-- settle the claim (four eyes).
ALTER TABLE claim ADD COLUMN opened_by VARCHAR(100);
