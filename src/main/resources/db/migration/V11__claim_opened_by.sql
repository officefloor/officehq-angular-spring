-- Who opened a claim: the signed-in user's name, set when the claim is opened. Null while DRAFT, and
-- for a claim voided before it was opened. That user cannot settle the claim (four eyes).
ALTER TABLE claim ADD COLUMN opened_by VARCHAR(200);
