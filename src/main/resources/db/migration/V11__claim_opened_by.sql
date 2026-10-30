-- Who opened a claim: set when the claim is opened, cleared once it is voided. The user who
-- opened a claim cannot settle it.
ALTER TABLE claim ADD COLUMN opened_by VARCHAR(100);
