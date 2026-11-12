-- The user a steward assigned a REVIEW claim's review to; null when no one.
ALTER TABLE claim ADD COLUMN review_assignee VARCHAR(100);
