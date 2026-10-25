-- The user a steward assigned a REVIEW claim to; null while it was never assigned. It stays after the
-- claim leaves REVIEW.
ALTER TABLE claim ADD COLUMN review_assigned VARCHAR(100);
