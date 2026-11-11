-- A steward can assign a REVIEW claim to a user: the claim keeps the user it was last assigned to.
ALTER TABLE claim ADD COLUMN review_assigned VARCHAR(100);
