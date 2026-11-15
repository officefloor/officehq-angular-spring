-- The user name a steward assigned the claim's review to while it was REVIEW (null when never
-- assigned); it stays once the claim is reviewed.
ALTER TABLE claim ADD COLUMN review_assignee VARCHAR(100);
