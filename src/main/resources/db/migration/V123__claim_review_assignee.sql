-- The user a steward assigned a claim in review to; null when never assigned. It stays once the
-- claim leaves review.
ALTER TABLE claim ADD COLUMN review_assignee VARCHAR(100);
