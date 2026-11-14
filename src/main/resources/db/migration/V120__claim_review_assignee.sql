-- The user a steward assigned a REVIEW claim to; null for a claim never assigned. It stays with the
-- claim once its review is done.
ALTER TABLE claim ADD COLUMN review_assignee VARCHAR(200);
