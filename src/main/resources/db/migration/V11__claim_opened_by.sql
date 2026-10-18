-- Who opened a claim: set when it is opened. A draft has none. The user who opened a claim may not
-- settle it (four eyes).
ALTER TABLE claim ADD COLUMN opened_by VARCHAR(255);
