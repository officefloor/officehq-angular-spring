-- The user a steward assigned the claim's review to (null when never assigned); it stays once the claim leaves REVIEW.
ALTER TABLE claim ADD COLUMN review_assigned VARCHAR(100);
