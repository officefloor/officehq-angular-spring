-- Who opened a claim: the signed-in user's name, set when it is opened. Null while a draft, or
-- when it was voided without being opened. The user who opened a claim may not settle it.
ALTER TABLE claim ADD COLUMN opened_by VARCHAR(255);
