-- The user a steward assigned a REVIEW claim to; none until one is assigned. It stays as it was once
-- the claim leaves REVIEW.
ALTER TABLE claim ADD COLUMN review_assignee VARCHAR(100);
