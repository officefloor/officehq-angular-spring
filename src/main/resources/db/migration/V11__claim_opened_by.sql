-- The user who opened a claim; none while it is DRAFT, or if it was voided without being opened.
-- That user cannot settle it (four eyes).
ALTER TABLE claim ADD COLUMN opened_by VARCHAR(100);
