-- A steward may assign a REVIEW claim to a user; none until assigned.
ALTER TABLE claim ADD COLUMN review_assignee VARCHAR(100);
