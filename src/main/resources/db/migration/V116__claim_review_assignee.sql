-- The user a steward assigned a REVIEW claim to; none when never assigned. It stays once the claim leaves REVIEW.
ALTER TABLE claim ADD COLUMN review_assignee VARCHAR(200);
