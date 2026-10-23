-- The user a steward assigned a REVIEW claim to for review; null until one is assigned. It is kept
-- when the claim leaves REVIEW.
ALTER TABLE claim ADD COLUMN review_assigned VARCHAR(200);
