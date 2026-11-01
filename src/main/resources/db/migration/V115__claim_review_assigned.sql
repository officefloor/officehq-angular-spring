-- The user a steward assigned a REVIEW claim to review (null when it was never assigned); it stays once assigned.
ALTER TABLE claim ADD COLUMN review_assigned VARCHAR(100);
