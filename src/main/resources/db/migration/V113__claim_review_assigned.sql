-- The user a steward assigned a claim in REVIEW to; null for one never assigned.
ALTER TABLE claim ADD COLUMN review_assigned VARCHAR(100);
